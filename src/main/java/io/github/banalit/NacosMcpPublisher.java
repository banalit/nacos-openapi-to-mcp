package io.github.banalit;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 应用就绪后，把 {@link McpSpecGenerator} 生成的配置幂等注册到 Nacos 3 MCP Registry。
 *
 * <p>对应 Admin API：POST /nacos/v3/admin/ai/mcp（不存在则创建），
 * 已存在时用 PUT + latest=true + overrideExisting=true 更新。
 * 注册失败只记录状态，不阻断 Web 应用启动。
 *
 * <p>幂等性说明：Nacos 对重复创建返回 HTTP 409（RestTemplate 会直接抛异常），
 * 因此必须先 GET 查存在性，再决定 POST 创建还是 PUT 更新。
 */
public class NacosMcpPublisher {

    private static final Logger log = LoggerFactory.getLogger(NacosMcpPublisher.class);

    private final McpSpecGenerator generator;

    private final McpOpenApiProperties props;

    private final RestTemplate rest = new RestTemplate();

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Map<String, Object> lastResult;

    {
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("status", "NOT_STARTED");
        lastResult = init;
    }

    public NacosMcpPublisher(McpSpecGenerator generator, McpOpenApiProperties props) {
        this.generator = generator;
        this.props = props;
    }

    public Map<String, Object> status() {
        return lastResult;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publish() {
        if (!props.isEnabled()) {
            Map<String, Object> disabled = new LinkedHashMap<>();
            disabled.put("status", "DISABLED");
            disabled.put("name", props.getName());
            lastResult = disabled;
            log.info("[OpenAPI->MCP] 自注册已禁用");
            return;
        }
        Map<String, Object> spec = generator.generate();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools =
                (List<Map<String, Object>>) ((Map<String, Object>) spec.get("toolSpecification")).get("tools");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", props.getName());
        result.put("toolCount", tools.size());
        result.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        try {
            String token = login();
            MultiValueMap<String, String> form = buildForm(spec);

            if (exists(token)) {
                JsonNode updated = exchangeJson(HttpMethod.PUT, "/v3/admin/ai/mcp", token, form, true);
                int code = updated.path("code").asInt(-1);
                result.put("status", code == 0 ? "UPDATED" : "FAILED");
                result.put("id", updated.path("data").asText(""));
                result.put("response", updated.toString());
            }
            else {
                JsonNode created = exchangeJson(HttpMethod.POST, "/v3/admin/ai/mcp", token, form, false);
                int code = created.path("code").asInt(-1);
                result.put("status", code == 0 ? "CREATED" : "FAILED");
                result.put("id", created.path("data").asText(""));
                result.put("response", created.toString());
            }
            log.info("[OpenAPI->MCP] 已把 {} 个工具注册到 Nacos MCP Registry: {} -> {}",
                    tools.size(), props.getName(), result.get("status"));
        }
        catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("[OpenAPI->MCP] 注册到 Nacos 失败（不影响应用启动）", e);
        }
        lastResult = result;
    }

    private String login() throws Exception {
        URI uri = UriComponentsBuilder.fromHttpUrl(base() + "/v3/auth/user/login")
                .queryParam("username", props.getUsername())
                .queryParam("password", props.getPassword())
                .build().encode().toUri();
        ResponseEntity<String> resp = rest.exchange(uri, HttpMethod.POST, HttpEntity.EMPTY, String.class);
        JsonNode body = mapper.readTree(resp.getBody());
        String token = body.path("accessToken").asText("");
        if (token.isEmpty()) {
            throw new IllegalStateException("Nacos 登录未返回 accessToken: " + body);
        }
        return token;
    }

    /** GET /v3/admin/ai/mcp 判断同名 MCP Server 是否已存在。 */
    private boolean exists(String token) {
        URI uri = UriComponentsBuilder.fromHttpUrl(base() + "/v3/admin/ai/mcp")
                .queryParam("mcpName", props.getName())
                .queryParam("namespaceId", props.getNamespace())
                .queryParam("accessToken", token)
                .build().encode().toUri();
        try {
            ResponseEntity<String> resp = rest.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, String.class);
            JsonNode body = mapper.readTree(resp.getBody());
            return body.path("code").asInt(-1) == 0
                    && body.hasNonNull("data")
                    && !body.path("data").isNull();
        }
        catch (Exception e) {
            log.debug("[OpenAPI->MCP] 查询同名 MCP Server 失败，按不存在处理: {}", e.getMessage());
            return false;
        }
    }

    private MultiValueMap<String, String> buildForm(Map<String, Object> spec) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mcpName", props.getName());
        form.add("namespaceId", props.getNamespace());
        form.add("version", props.getVersion());
        form.add("serverSpecification", generator.toJson(spec.get("serverSpecification")));
        form.add("toolSpecification", generator.toJson(spec.get("toolSpecification")));
        form.add("endpointSpecification", generator.toJson(spec.get("endpointSpecification")));
        return form;
    }

    private JsonNode exchangeJson(HttpMethod method, String path, String token,
            MultiValueMap<String, String> form, boolean update) throws Exception {
        if (update) {
            form.add("latest", "true");
            form.add("overrideExisting", "true");
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(base() + path)
                .queryParam("accessToken", token)
                .build().encode().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
        ResponseEntity<String> resp = rest.exchange(uri, method, entity, String.class);
        return mapper.readTree(resp.getBody());
    }

    private String base() {
        String addr = props.getNacosAddr();
        if (!addr.startsWith("http://") && !addr.startsWith("https://")) {
            addr = "http://" + addr;
        }
        return addr + "/nacos";
    }
}
