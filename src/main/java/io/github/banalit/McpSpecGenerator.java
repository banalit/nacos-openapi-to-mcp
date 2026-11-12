package io.github.banalit;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.banalit.annotation.McpTool;
import io.swagger.v3.oas.annotations.Operation;

import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

/**
 * 扫描所有带 {@link McpTool} 的 Spring MVC 方法，读取 Swagger 描述，
 * 生成 Nacos MCP Registry 的 serverSpecification / toolSpecification / endpointSpecification。
 *
 * <p>生成的 {@code toolSpecification.tools} 就是标准 MCP tools schema
 * （name / description / inputSchema）；{@code toolsMeta} 是 Higress 数据面
 * 需要的 json-go-template 协议转化模板，二者缺一不可。
 *
 * <p>endpointSpecification 使用 {@code type=REF} 引用宿主应用已注册到 Nacos 的
 * 服务实例，框架自身不做实例注册与心跳。
 */
public class McpSpecGenerator {

    private final ApplicationContext context;

    private final McpOpenApiProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    public McpSpecGenerator(ApplicationContext context, McpOpenApiProperties props) {
        this.context = context;
        this.props = props;
    }

    public Map<String, Object> generate() {
        List<Map<String, Object>> tools = new ArrayList<>();
        Map<String, Object> toolsMeta = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();

        for (Object bean : context.getBeansWithAnnotation(
                org.springframework.web.bind.annotation.RestController.class).values()) {
            Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
            Set<Method> methods = new LinkedHashSet<>();
            ReflectionUtils.doWithMethods(userClass,
                    methods::add,
                    m -> AnnotatedElementUtils.hasAnnotation(m, McpTool.class));
            for (Method method : methods) {
                McpTool ann = AnnotatedElementUtils.findMergedAnnotation(method, McpTool.class);
                if (ann == null || !ann.enabled()) {
                    continue;
                }
                ToolSpec spec = buildTool(userClass, method, ann);
                tools.add(spec.tool);
                toolsMeta.put(spec.name, spec.meta);
                sources.add(spec.source);
            }
        }
        tools.sort(Comparator.comparing(t -> String.valueOf(t.get("name"))));

        Map<String, Object> toolSpec = new LinkedHashMap<>();
        toolSpec.put("tools", tools);
        toolSpec.put("toolsMeta", toolsMeta);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", props.getName());
        result.put("frontProtocol", props.getFrontProtocol());
        result.put("serverSpecification", serverSpec(tools.size()));
        result.put("toolSpecification", toolSpec);
        result.put("endpointSpecification", endpointSpec());
        result.put("sources", sources);
        return result;
    }

    public String toJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 MCP 配置", e);
        }
    }

    private Map<String, Object> serverSpec(int toolCount) {
        Map<String, Object> serviceRef = new LinkedHashMap<>();
        serviceRef.put("namespaceId", props.getNamespace());
        serviceRef.put("groupName", props.getGroup());
        serviceRef.put("serviceName", props.getServiceName());
        serviceRef.put("transportProtocol", "http");

        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("serviceRef", serviceRef);
        remote.put("exportPath", props.getExportPath());

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("namespaceId", props.getNamespace());
        spec.put("name", props.getName());
        // protocol=http 表示「存量 HTTP API 转 MCP」，由 Higress 做协议转换
        spec.put("protocol", "http");
        spec.put("frontProtocol", props.getFrontProtocol());
        spec.put("description", props.getDescription() + "（自动生成 " + toolCount + " 个工具）");
        spec.put("version", props.getVersion());
        spec.put("enabled", true);
        spec.put("capabilities", List.of("TOOL"));
        spec.put("remoteServerConfig", remote);
        return spec;
    }

    private Map<String, Object> endpointSpec() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("namespaceId", props.getNamespace());
        data.put("groupName", props.getGroup());
        data.put("serviceName", props.getServiceName());
        Map<String, Object> spec = new LinkedHashMap<>();
        // REF 引用宿主应用已注册到 Nacos 的服务实例，避免重复实现注册与心跳
        spec.put("type", "REF");
        spec.put("data", data);
        return spec;
    }

    private ToolSpec buildTool(Class<?> controller, Method method, McpTool ann) {
        String name = StringUtils.hasText(ann.name()) ? ann.name() : method.getName();
        String path = resolvePath(controller, method);
        String httpMethod = resolveHttpMethod(method);

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        Map<String, String> argsPosition = new LinkedHashMap<>();
        collectParams(method, properties, required, argsPosition);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        Operation op = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
        List<String> descParts = new ArrayList<>();
        if (op != null && StringUtils.hasText(op.summary())) {
            descParts.add(op.summary().trim());
        }
        if (op != null && StringUtils.hasText(op.description())) {
            descParts.add(op.description().trim());
        }
        String description = descParts.isEmpty()
                ? (StringUtils.hasText(ann.description()) ? ann.description() : method.getName())
                : String.join(" - ", descParts);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);

        Map<String, Object> requestTemplate = new LinkedHashMap<>();
        requestTemplate.put("url", path);
        requestTemplate.put("method", httpMethod);
        if ("GET".equals(httpMethod) && argsPosition.containsValue("query")) {
            requestTemplate.put("argsToUrlParam", true);
        }
        Map<String, Object> responseTemplate = new LinkedHashMap<>();
        responseTemplate.put("body", ann.responseTemplate());

        Map<String, Object> goTemplate = new LinkedHashMap<>();
        goTemplate.put("requestTemplate", requestTemplate);
        goTemplate.put("argsPosition", argsPosition);
        goTemplate.put("responseTemplate", responseTemplate);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("enabled", true);
        meta.put("invokeContext", Map.of());
        meta.put("templates", Map.of("json-go-template", goTemplate));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("tool", name);
        source.put("javaMethod", controller.getSimpleName() + "#" + method.getName());
        source.put("swaggerSummary", op == null ? "" : op.summary());
        source.put("path", path);
        source.put("httpMethod", httpMethod);
        source.put("argsPosition", argsPosition);

        return new ToolSpec(name, tool, meta, source);
    }

    private void collectParams(Method method, Map<String, Object> properties,
            List<String> required, Map<String, String> argsPosition) {
        for (Parameter p : method.getParameters()) {
            Class<?> type = p.getType();
            if (type.getName().startsWith("jakarta.servlet.")) {
                continue;
            }
            RequestParam rp = p.getAnnotation(RequestParam.class);
            PathVariable pv = p.getAnnotation(PathVariable.class);
            RequestHeader rh = p.getAnnotation(RequestHeader.class);
            RequestBody rb = p.getAnnotation(RequestBody.class);
            io.swagger.v3.oas.annotations.Parameter sp =
                    p.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class);

            String name = p.getName();
            String position = "query";
            boolean isRequired = true;

            if (rp != null) {
                name = firstNonEmpty(rp.value(), rp.name(), p.getName());
                position = "query";
                isRequired = rp.required() && ValueConstants.DEFAULT_NONE.equals(rp.defaultValue());
            }
            else if (pv != null) {
                name = firstNonEmpty(pv.value(), pv.name(), p.getName());
                position = "path";
                isRequired = true;
            }
            else if (rh != null) {
                name = firstNonEmpty(rh.value(), rh.name(), p.getName());
                position = "header";
                isRequired = rh.required();
            }
            else if (rb != null) {
                position = "body";
                isRequired = rb.required();
            }

            Map<String, Object> propSchema = new LinkedHashMap<>();
            propSchema.put("type", jsonType(type));
            if (sp != null && StringUtils.hasText(sp.description())) {
                propSchema.put("description", sp.description().trim());
            }
            properties.put(name, propSchema);
            argsPosition.put(name, position);
            if (isRequired) {
                required.add(name);
            }
        }
    }

    private String resolvePath(Class<?> controller, Method method) {
        String classPath = firstPath(
                AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
        String methodPath = firstPath(
                AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
        String raw = (classPath + "/" + methodPath).replaceAll("/+", "/");
        if (!raw.startsWith("/")) {
            raw = "/" + raw;
        }
        if (raw.length() > 1 && raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    private String firstPath(RequestMapping mapping) {
        if (mapping == null) {
            return "";
        }
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return paths.length > 0 ? paths[0] : "";
    }

    private String resolveHttpMethod(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        return mapping != null && mapping.method().length > 0 ? mapping.method()[0].name() : "GET";
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return "";
    }

    private String jsonType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class) {
            return "integer";
        }
        if (type == float.class || type == Float.class || type == double.class || type == Double.class
                || BigDecimal.class.isAssignableFrom(type)) {
            return "number";
        }
        if (CharSequence.class.isAssignableFrom(type) || type == char.class || type == Character.class) {
            return "string";
        }
        return "object";
    }

    private static final class ToolSpec {

        private final String name;

        private final Map<String, Object> tool;

        private final Map<String, Object> meta;

        private final Map<String, Object> source;

        private ToolSpec(String name, Map<String, Object> tool, Map<String, Object> meta,
                Map<String, Object> source) {
            this.name = name;
            this.tool = tool;
            this.meta = meta;
            this.source = source;
        }
    }
}
