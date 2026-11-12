package io.github.banalit.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 Spring MVC 方法需要被导出为 MCP Tool。
 *
 * <p>与 Spring AI 的 {@code @Tool} 不同：本注解不要求应用实现 MCP 协议，
 * 而是由框架在启动时读取方法上的 Swagger/OpenAPI 描述与 MVC 映射，
 * 生成 Nacos MCP Registry 需要的 tools schema 与 json-go-template 协议转化模板，
 * 由 Higress 完成 REST 到 MCP 的运行时转换。
 *
 * <p>用法：给存量 REST 方法加本注解与 {@code @Operation} / {@code @Parameter}，
 * 引入本框架依赖后应用启动即自动注册到 Nacos。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpTool {

    /** MCP tool 名称；留空则使用 Java 方法名。 */
    String name() default "";

    /** 方法没有 Swagger 注解时的兜底描述。 */
    String description() default "";

    /**
     * Higress responseTemplate.body 模板。
     * 默认直接输出后端原始 JSON 文本；用 toJson . 会被二次序列化成带引号的字符串。
     * 需要更好可读性时可改成字段级模板，例如 "用户：{{ .name }}，城市：{{ .city }}"。
     */
    String responseTemplate() default "{{ . }}";

    boolean enabled() default true;
}
