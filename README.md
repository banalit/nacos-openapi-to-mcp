# nacos-openapi-to-mcp

## 这个框架解决什么问题

很多企业的线上系统还在用 **JDK 8 + Spring Boot 2.x**，短期内无法升级。当业务需要把这些系统的 REST API 接入 AI Agent（MCP 协议）时，面临两个痛点：

1. **Spring AI Alibaba 的 `@Tool` 要求 JDK 17 + Spring Boot 3 + spring-ai 生态**——存量系统用不了
2. **让存量应用自己实现 MCP 协议**——改动太大，风险太高，不现实

本框架的思路是：**应用不动，只加一个注解**。给已有的 REST 方法加上 `@McpTool` + Swagger `@Operation`，应用启动时框架自动扫描、生成 Nacos 3 MCP Registry 需要的定义（工具 schema + 协议转化模板），注册到 Nacos。**Higress 网关**从 Nacos 拉取定义后，自动把你的 REST 端点暴露为 MCP 服务（`/mcp/{name}`），由网关完成 REST ↔ MCP 的运行时协议转换。

**应用本身不需要实现 MCP 协议，不依赖 Spring AI，不需要升级 JDK。**

```
@McpTool + @Operation 标注在已有的 REST 方法上
        │
        ▼  框架启动时自动扫描（McpSpecGenerator）
生成 Nacos 3 MCP Registry 三段 JSON
        │  框架自动注册（NacosMcpPublisher，幂等：先查存在再 POST/PUT）
        ▼
Nacos MCP Registry  ──Higress nacos3 watcher 自动拉取──▶  网关路由 /mcp/{name}
        │
        ▼  MCP 客户端调用 tools/list、tools/call
   你的原始 REST 接口（由 Higress 做 REST→MCP 协议转换）
```

## 核心特性

- **兼容 JDK 8**：编译目标 `source/target=8`，字节码版本 52，在 JDK 8 / 11 / 17 / 21 上均可运行
- **兼容 Spring Boot 2.x 和 3.x**：不继承 `spring-boot-starter-parent`，通过 BOM import + 属性 `${spring-boot.version}`（默认 `2.7.18`）托管依赖版本，消费方用自己的 Boot 版本时由其 parent BOM 主导，互不冲突
- **不依赖 Spring AI**：框架只用到 Spring Boot 自动装配 + Spring MVC 注解反射 + Jackson + RestTemplate，零 AI 生态依赖
- **自动装配，加依赖即用**：同时注册 `spring.factories`（Boot 2.x）和 `AutoConfiguration.imports`（Boot 3.x），无需组件扫描或 `@EnableXxx`
- **幂等注册**：先 `GET` 查存在性再决定 `POST` 创建 / `PUT` 更新，重启不会因 HTTP 409 崩溃
- **所有 Bean 可覆盖**：带 `@ConditionalOnMissingBean`，业务可自定义 `McpSpecGenerator` / `NacosMcpPublisher`

## 与 Spring AI Alibaba `@Tool` 的区别

| | SAA `@Tool` | 本框架 `@McpTool` |
| --- | --- | --- |
| JDK 要求 | 17+ | **8+** |
| Spring Boot | 3.x | **2.x / 3.x** |
| 依赖 Spring AI | 是 | **否** |
| 应用实现 MCP 协议 | 是（原生 MCP Server） | **否（保持普通 REST API）** |
| 谁做 REST↔MCP 转换 | 应用自身 | **Higress 网关** |
| 适用场景 | 新建原生 MCP 服务 | **存量 REST API 改造为 MCP 工具** |

> 如果你的项目已经是 JDK 17 + Spring Boot 3，可以直接用 Spring AI Alibaba 的 `@Tool`，不需要这个框架。本框架专为**无法升级的环境**设计。

## 前置条件

- **JDK 8+**
- **Spring Boot 2.x 或 3.x**
- **Nacos 3.x**（开启 MCP Registry），应用启动时能访问到
- **Higress** 配置了 `type: nacos3` 服务来源且 `enableMCPServer: true`，用于从 Nacos 自动发现 MCP 定义
- **一个已注册的 Nacos 服务实例**：`endpointSpecification` 使用 `type=REF` 引用一个已存在的服务名（通常是应用自身已注册到 Nacos 的服务实例），框架本身不做实例注册和心跳

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.banalit</groupId>
    <artifactId>nacos-openapi-to-mcp</artifactId>
    <version>1.0.0</version>
</dependency>
```

还需要 Swagger 注解（业务代码声明注解、框架读取描述）：

```xml
<dependency>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-annotations</artifactId>
    <version>2.2.25</version>
</dependency>
```

### 2. 给 REST 方法加注解

```java
@RestController
@RequestMapping("/api")
public class DemoController {

    @McpTool
    @Operation(summary = "打招呼", description = "根据姓名返回问候语")
    @GetMapping("/greet")
    public Map<String, Object> greet(
            @Parameter(description = "用户姓名", required = true) @RequestParam String name,
            @Parameter(description = "语言") @RequestParam(required = false, defaultValue = "zh") String lang) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("message", "zh".equals(lang) ? "你好, " + name + "!" : "Hello, " + name + "!");
        return m;
    }

    @McpTool(name = "searchProducts")
    @Operation(summary = "搜索商品", description = "按关键词搜索商品列表")
    @GetMapping("/products")
    public Map<String, Object> products(
            @Parameter(description = "搜索关键词") @RequestParam String keyword,
            @Parameter(description = "最大返回数量") @RequestParam(required = false, defaultValue = "10") int limit) {
        // ... 原有业务逻辑不变
    }
}
```

不需要 `@Tool`，不需要 MCP 协议代码，就是普通的 REST Controller。

### 3. 配置 Nacos 连接

```yaml
mcp:
  openapi:
    enabled: true
    name: my-openapi-mcp              # Higress 会暴露 /mcp/my-openapi-mcp
    version: 1.0.0
    nacos-addr: ${NACOS_ADDR:127.0.0.1:8848}
    username: ${NACOS_USERNAME:nacos}
    password: ${NACOS_PASSWORD:nacos}
    namespace: public
    group: DEFAULT_GROUP
    service-name: my-app-service      # 已注册到 Nacos 的服务实例名（REF 引用）
    export-path: /
    front-protocol: mcp-streamable    # 只能是 stdio / mcp-sse / mcp-streamable
```

启动应用，日志会输出：

```
[OpenAPI->MCP] 已把 2 个工具注册到 Nacos MCP Registry: my-openapi-mcp -> CREATED
```

Higress 自动订阅并创建 `/mcp/my-openapi-mcp` 路由。通过 MCP 协议验证：

```bash
curl -X POST http://<higress网关>/mcp/my-openapi-mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 配置项参考

所有配置项前缀为 `mcp.openapi.*`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关，`false` 关闭扫描和注册 |
| `name` | `openapi-mcp` | Nacos 中的 MCP Server 名称，Higress 暴露为 `/mcp/{name}` |
| `version` | `1.0.0` | MCP Server 版本 |
| `description` | 自动生成 | MCP Server 描述 |
| `nacos-addr` | `127.0.0.1:8848` | Nacos 地址（host:port） |
| `username` | `nacos` | Nacos 用户名 |
| `password` | `nacos` | Nacos 密码 |
| `namespace` | `public` | Nacos 命名空间 ID |
| `group` | `DEFAULT_GROUP` | Nacos 分组 |
| `service-name` | `openapi-mcp-service` | `endpointSpecification` 引用的已注册 Nacos 服务实例名（type=REF） |
| `export-path` | `/` | MCP Server 的导出路径 |
| `front-protocol` | `mcp-streamable` | 必须是 `stdio` / `mcp-sse` / `mcp-streamable`，其他值 Higress 会跳过 |
| `expose-endpoints` | `true` | 是否暴露 `/mcp-openapi/spec` 和 `/mcp-openapi/status` 观察端点 |

## @McpTool 注解

```java
@Target(METHOD) @Retention(RUNTIME)
public @interface McpTool {
    String name() default "";           // MCP 工具名，留空用 Java 方法名
    String description() default "";    // 没有 Swagger @Operation 时的兜底描述
    String responseTemplate() default "{{ . }}";  // Higress responseTemplate.body
    boolean enabled() default true;     // 设为 false 排除该方法
}
```

**`responseTemplate` 说明：**
- 默认 `{{ . }}` 直接输出后端原始 JSON（推荐）
- 不要用 `{{ toJson . }}`，会二次序列化成带引号的字符串
- 需要可读文本时可自定义：`"用户：{{ .name }}，城市：{{ .city }}"`

## 观察端点

`expose-endpoints=true`（默认开启）时：

- `GET /mcp-openapi/spec` — 查看将提交给 Nacos 的完整三段配置 JSON
- `GET /mcp-openapi/status` — 注册结果：名称、工具数、状态（`CREATED` / `UPDATED` / `ERROR`）、时间

## 幂等注册机制

`NacosMcpPublisher` 在 `ApplicationReadyEvent` 时先 `GET /v3/admin/ai/mcp?mcpName=...` 查存在性：
- 不存在 → `POST` 创建
- 已存在 → `PUT` 更新（带 `latest=true` + `overrideExisting=true`）

避免了 Nacos 对重复 `POST` 返回 HTTP 409 导致的启动崩溃。

## 从源码构建

```bash
# 框架（install 到本地 ~/.m2）
mvn -f nacos-openapi-to-mcp/pom.xml clean install

# 你的应用
mvn -f your-app/pom.xml clean package
```

框架不继承 `spring-boot-starter-parent`，通过 `${spring-boot.version}` 属性（默认 `2.7.18`）导入 BOM。需要用 Boot 3 构建时：

```bash
mvn -Dspring-boot.version=3.5.10 clean install
```

## 架构说明

- **自动装配**：同时通过 `META-INF/spring.factories`（Spring Boot 2.x）和 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（Spring Boot 3.x）注册，加依赖即生效。配置类用 `@Configuration`（非 `@AutoConfiguration`），在 Boot 2.x 和 3.x 上都兼容
- **Bean 可覆盖**：所有 Bean 带 `@ConditionalOnMissingBean`，可自定义 `McpSpecGenerator` 或 `NacosMcpPublisher`
- **框架不做的事**：不注册 Nacos 服务实例、不发心跳。`service-name` 指向一个已存在的实例（可来自 Nacos 服务发现、SAA mcp-registry 或手工注册），MCP 定义通过 `endpointSpecification.type=REF` 引用它

## License

MIT
