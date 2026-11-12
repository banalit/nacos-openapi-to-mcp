package io.github.banalit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAPI-to-MCP 自注册框架配置（前缀 {@code mcp.openapi}）。
 *
 * <p>所有字段都有合理默认值；Nacos 地址与凭据建议用环境变量占位符注入。
 */
@ConfigurationProperties(prefix = "mcp.openapi")
public class McpOpenApiProperties {

    private boolean enabled = true;

    /** Nacos MCP Registry 中的 MCP Server 名称，Higress 会暴露为 /mcp/{name}。 */
    private String name = "openapi-mcp";

    private String version = "1.0.0";

    private String description = "由 Spring Boot OpenAPI 注解自动生成的 REST-to-MCP 服务";

    private String nacosAddr = "127.0.0.1:8848";

    private String username = "nacos";

    private String password = "nacos";

    private String namespace = "public";

    private String group = "DEFAULT_GROUP";

    /**
     * endpointSpecification 引用的 Nacos 服务实例名。
     * 通常填应用自身已注册到 Nacos 的服务名（例如由 SAA mcp-registry 注册的实例），
     * 这样无需自己实现服务注册与心跳；也可以指向任意 Higress 可达的服务实例。
     */
    private String serviceName = "openapi-mcp-service";

    private String exportPath = "/";

    /** 必须是 Nacos 枚举值之一：stdio / mcp-sse / mcp-streamable。 */
    private String frontProtocol = "mcp-streamable";

    /** 是否暴露 /mcp-openapi/spec、/mcp-openapi/status 观察端点（便于排查注册结果）。 */
    private boolean exposeEndpoints = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getNacosAddr() {
        return nacosAddr;
    }

    public void setNacosAddr(String nacosAddr) {
        this.nacosAddr = nacosAddr;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getExportPath() {
        return exportPath;
    }

    public void setExportPath(String exportPath) {
        this.exportPath = exportPath;
    }

    public String getFrontProtocol() {
        return frontProtocol;
    }

    public void setFrontProtocol(String frontProtocol) {
        this.frontProtocol = frontProtocol;
    }

    public boolean isExposeEndpoints() {
        return exposeEndpoints;
    }

    public void setExposeEndpoints(boolean exposeEndpoints) {
        this.exposeEndpoints = exposeEndpoints;
    }
}
