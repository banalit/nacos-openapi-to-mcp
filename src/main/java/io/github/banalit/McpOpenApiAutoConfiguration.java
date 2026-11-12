package io.github.banalit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * OpenAPI-to-MCP 自动装配入口：业务应用只需引入
 * {@code io.github.banalit:nacos-openapi-to-mcp} 依赖即可生效，无需组件扫描。
 *
 * <p>装配内容：
 * <ul>
 *   <li>{@link McpSpecGenerator} —— 扫描 {@code @McpTool} 方法生成 Nacos 三段 JSON</li>
 *   <li>{@link NacosMcpPublisher} —— 应用就绪后幂等注册到 Nacos MCP Registry</li>
 *   <li>{@link McpOpenApiController} —— 观察端点（可用 {@code mcp.openapi.expose-endpoints=false} 关闭）</li>
 * </ul>
 * 所有 Bean 均带 {@code @ConditionalOnMissingBean}，业务应用可自行覆盖。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mcp.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(McpOpenApiProperties.class)
public class McpOpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public McpSpecGenerator mcpSpecGenerator(ApplicationContext applicationContext,
            McpOpenApiProperties properties) {
        return new McpSpecGenerator(applicationContext, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public NacosMcpPublisher nacosMcpPublisher(McpSpecGenerator generator,
            McpOpenApiProperties properties) {
        return new NacosMcpPublisher(generator, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "mcp.openapi", name = "expose-endpoints",
            havingValue = "true", matchIfMissing = true)
    public McpOpenApiController mcpOpenApiController(McpSpecGenerator generator,
            NacosMcpPublisher publisher) {
        return new McpOpenApiController(generator, publisher);
    }
}
