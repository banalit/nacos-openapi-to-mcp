# nacos-openapi-to-mcp

A Spring Boot 3 auto-configuration library that turns your **existing REST APIs into MCP tools** — without implementing the MCP protocol yourself.

Add `@McpTool` + Swagger `@Operation`/`@Parameter` to any `@RestController` method. On application startup the framework scans those methods, reads their Swagger descriptions and Spring MVC mappings, generates the Nacos 3 MCP Registry definition (tools schema + `json-go-template` protocol-conversion templates), and registers it via the Nacos Admin API. **Higress** then picks up the definition from Nacos and exposes your REST endpoint as a streamable MCP server at `/mcp/{name}`.

```
@McpTool + @Operation on a REST method
        │
        ▼  McpSpecGenerator (startup scan)
Nacos 3 MCP Registry JSON
        │  NacosMcpPublisher (idempotent POST/PUT on ApplicationReadyEvent)
        ▼
Nacos MCP Registry  ──Higress nacos3 watcher──▶  Envoy route /mcp/{name}
        │
        ▼  MCP client calls tools/list, tools/call
   your original REST endpoint (REST-to-MCP by Higress)
```

## How it differs from Spring AI Alibaba `@Tool`

| | SAA `@Tool` | This library `@McpTool` |
| --- | --- | --- |
| App implements MCP protocol | Yes (native MCP server) | No (stays a plain REST API) |
| Who converts REST↔MCP | The app itself | Higress at the gateway |
| Use case | New native MCP services | Existing REST APIs → MCP with zero protocol code |

## Requirements

- **Java 8+** (compiled to Java 8 bytecode; works on JDK 8, 11, 17, 21)
- **Spring Boot 2.x or 3.x** (default BOM `2.7.18` for Boot 2 / JDK 8 compatibility; overridable to 3.x). The library does **not** inherit `spring-boot-starter-parent` — it imports the `spring-boot-dependencies` BOM with a `${spring-boot.version}` property, so your project's own Boot version takes precedence and there is no version conflict.
- **Nacos 3.x** (MCP Registry enabled) reachable from the application at startup.
- **Higress** configured with a `type: nacos3` service source and `enableMCPServer: true` so it subscribes to MCP definitions from Nacos.
- **A registered service instance** in Nacos that Higress can route to (the `endpointSpecification` uses `type=REF`, referencing an existing service name). Typically your app already registers itself via Spring AI Alibaba's `mcp-registry` or Nacos service discovery — just point `mcp.openapi.service-name` at it.

> **Why JDK 8 + Boot 2.x?** This framework exists for **legacy systems that cannot upgrade their JDK or Spring Boot version**. If your project is already on JDK 17 + Boot 3, you can use Spring AI Alibaba's `@Tool` directly — you don't need this library.

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.banalit</groupId>
    <artifactId>nacos-openapi-to-mcp</artifactId>
    <version>1.0.0</version>
</dependency>
```

You also need Swagger annotations on the compile classpath (the framework reads them; your code declares them):

```xml
<dependency>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-annotations</artifactId>
    <version>2.2.25</version>
</dependency>
```

### 2. Annotate your REST methods

```java
@RestController
@RequestMapping("/openapi")
public class OpenApiDemoController {

    @McpTool
    @Operation(summary = "Query user by name",
               description = "Returns lab user info by name and city; callable by MCP clients")
    @GetMapping("/user")
    public Map<String, Object> user(
            @Parameter(description = "User name", required = true) @RequestParam String name,
            @Parameter(description = "City") @RequestParam(required = false, defaultValue = "Shenzhen") String city) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("city", city);
        m.put("email", name.toLowerCase() + "@lab.example.com");
        return m;
    }

    @McpTool(name = "listUsers", responseTemplate = "Total {{ .total }} users: {{ .items }}")
    @Operation(summary = "List all users", description = "Returns the built-in user list")
    @GetMapping("/users")
    public Map<String, Object> users() {
        return Map.of("total", 2, "items", List.of(
                Map.of("name", "Luke", "city", "Shenzhen"),
                Map.of("name", "Anna", "city", "Hangzhou")));
    }
}
```

No `@Tool`, no MCP protocol code — just a normal REST controller.

### 3. Configure Nacos connection

```yaml
mcp:
  openapi:
    enabled: true
    name: my-openapi-mcp              # Higress exposes /mcp/my-openapi-mcp
    version: 1.0.0
    nacos-addr: ${NACOS_ADDR:127.0.0.1:8848}
    username: ${NACOS_USERNAME:nacos}
    password: ${NACOS_PASSWORD:nacos}
    namespace: public
    group: DEFAULT_GROUP
    service-name: my-app-service      # existing Nacos service instance (REF)
    export-path: /
    front-protocol: mcp-streamable    # must be one of: stdio / mcp-sse / mcp-streamable
```

Start the app. On `ApplicationReadyEvent` the framework logs:

```
[OpenAPI->MCP] 已把 2 个工具注册到 Nacos MCP Registry: my-openapi-mcp -> CREATED
```

Higress automatically subscribes and creates the `/mcp/my-openapi-mcp` route. Verify from any MCP client:

```bash
curl -X POST http://<higress-gateway>/mcp/my-openapi-mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Configuration reference

All properties are under prefix `mcp.openapi.*`:

| Property | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master switch; `false` disables scanning and registration entirely |
| `name` | `openapi-mcp` | MCP Server name in Nacos; Higress exposes `/mcp/{name}` |
| `version` | `1.0.0` | MCP Server version |
| `description` | auto | MCP Server description |
| `nacos-addr` | `127.0.0.1:8848` | Nacos server address (host:port) |
| `username` | `nacos` | Nacos username |
| `password` | `nacos` | Nacos password |
| `namespace` | `public` | Nacos namespace ID |
| `group` | `DEFAULT_GROUP` | Nacos group |
| `service-name` | `openapi-mcp-service` | Existing Nacos service instance referenced by `endpointSpecification` (type=REF) |
| `export-path` | `/` | Export path in the MCP server spec |
| `front-protocol` | `mcp-streamable` | Must be `stdio` / `mcp-sse` / `mcp-streamable`; any other value causes Higress to skip the definition |
| `expose-endpoints` | `true` | Whether to expose `/mcp-openapi/spec` and `/mcp-openapi/status` observation endpoints |

## The `@McpTool` annotation

```java
@Target(METHOD) @Retention(RUNTIME)
public @interface McpTool {
    String name() default "";           // MCP tool name; defaults to Java method name
    String description() default "";    // Fallback when no Swagger @Operation is present
    String responseTemplate() default "{{ . }}";  // Higress responseTemplate.body
    boolean enabled() default true;     // Set false to exclude this method
}
```

**`responseTemplate` tips:**
- Default `{{ . }}` outputs the backend JSON response as-is (recommended for structured data).
- Avoid `{{ toJson . }}` — it double-serializes into a quoted string.
- For readable text: `"User: {{ .name }}, City: {{ .city }}"`.

## Observation endpoints

When `expose-endpoints=true` (default):

- `GET /mcp-openapi/spec` — the full Nacos MCP definition JSON (server/tool/endpoint specs) that was submitted
- `GET /mcp-openapi/status` — registration result: name, tool count, status (`CREATED`/`UPDATED`), timestamp

## How idempotency works

`NacosMcpPublisher` first does a `GET /v3/admin/ai/mcp?mcpName=...` to check existence:
- Not found → `POST /v3/admin/ai/mcp` (create)
- Already exists → `PUT /v3/admin/ai/mcp` (update)

This avoids the HTTP 409 that Nacos returns on duplicate `POST`, which would crash a naive create-only flow on every restart after the first.

## Build from source

```bash
# Framework (installs to local ~/.m2)
mvn -f nacos-openapi-to-mcp/pom.xml clean install

# Your application
mvn -f your-app/pom.xml clean package
```

The framework does **not** pin `spring-boot-starter-parent`. It imports the `spring-boot-dependencies` BOM via a `${spring-boot.version}` property (default `2.7.18` for JDK 8 / Boot 2 compatibility). To build/test against a different Boot version:

```bash
# Build against Spring Boot 3.x
mvn -Dspring-boot.version=3.5.10 clean install
```

## Architecture notes

- **Auto-configuration**: Registered via both `META-INF/spring.factories` (Spring Boot 2.x) and `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot 3.x) — adding the dependency is enough, no component scanning or `@EnableXxx` needed. The `@Configuration` class works across both Boot 2.x and 3.x.
- **Overridable beans**: All beans are annotated `@ConditionalOnMissingBean`, so you can provide your own `McpSpecGenerator` or `NacosMcpPublisher` to customize behavior.
- **What the framework does NOT do**: It does not register a Nacos service instance or send heartbeats. It assumes `service-name` points to an already-registered instance (e.g. from SAA `mcp-registry`, Nacos service discovery, or manual registration). The MCP definition it writes uses `endpointSpecification.type=REF` to reference that instance.

## License

MIT
