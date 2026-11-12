package io.github.banalit;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 观察端点：查看自动生成的 MCP/Nacos 配置与注册状态，便于排查同步问题。
 *
 * <p>可用 {@code mcp.openapi.expose-endpoints=false} 关闭。
 */
@RestController
@RequestMapping("/api/mcp/openapi")
public class McpOpenApiController {

    private final McpSpecGenerator generator;

    private final NacosMcpPublisher publisher;

    public McpOpenApiController(McpSpecGenerator generator, NacosMcpPublisher publisher) {
        this.generator = generator;
        this.publisher = publisher;
    }

    /** 完整查看将被提交给 Nacos 的三段配置，便于对照 OpenAPI 描述。 */
    @GetMapping("/spec")
    public Map<String, Object> spec() {
        return generator.generate();
    }

    /** 启动后自动注册的结果（CREATED / UPDATED / ERROR）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return publisher.status();
    }
}
