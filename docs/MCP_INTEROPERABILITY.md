# DataGuard — MCP 标准协议与客户端验收

## 服务端实现

- 官方 MCP Java SDK 0.18.2，Spring WebMVC Stateless Streamable HTTP：`POST /mcp` JSON-RPC，`GET /mcp` = 405（不提供 SSE 事件流）。
- SDK 自行处理 initialize、tools/list、tools/call、ping、通知、版本协商和工具 JSON Schema。无自制 JSON-RPC Controller。
- 独立 MCP Bearer Token + Origin 前置过滤器，仅支持有效 DataGuard Agent Token（登录 UI 的 Cookie 不能用于 MCP）。
- `DG_ALLOWED_MCP_ORIGINS` 配置受信任 Origin 列表。桌面/服务端无 Origin 请求允许；显式不可信 Origin 返回 403。
- 未认证 401，非法协议头 400，通知成功 202，GET SSE 不受支持 405。
- 不创建 MCP 会话 ID；这是按协议允许的**无状态**实现，并不提供 SSE 推送、工具变更通知、客户端采样或服务端主动请求；不能声称支持这些可选能力。
- SQL 权限、审批、审计、读限流保持在 `AgentCatalogService`、`SqlQueryService`、`PolicyEngine` 内，不因传输层改变而绕过。
- 当前采用静态 Bearer Agent Token；**不是 OAuth 2.1 Authorization Server**，未暴露 PRM、AS Metadata、PKCE、DCR、CIMD 等 OAuth 登录流程。企业级云客户端如要求 OAuth，需要另建安全的授权集成。

## 主流客户端配置（占位符，无密钥写入仓库）

服务地址 `https://110.42.132.44/mcp`，使用有效 Agent Token；实际生产建议迁移到域名和独立安全凭据存储。

### Cursor

在用户级 `~/.cursor/mcp.json` 写入：

```json
{
  "mcpServers": {
    "dataguard": {
      "url": "https://110.42.132.44/mcp",
      "headers": {"Authorization": "Bearer <AGENT_TOKEN>"}
    }
  }
}
```

打开 Cursor 的 MCP 服务管理页面，确认六个工具出现，调用 `list_datasources`、`execute_sql_readonly`。密钥不要提交 Git，推荐个人私有配置。

### VS Code / GitHub Copilot

用户 Profile `mcp.json` 或工作区 `.vscode/mcp.json`：

```json
{
  "servers": {
    "dataguard": {
      "type": "http",
      "url": "https://110.42.132.44/mcp",
      "headers": {"Authorization": "Bearer ${env:DATAGUARD_AGENT_TOKEN}"}
    }
  }
}
```

如使用新的 Copilot Portable `.mcp.json`，顶层名称换成 `mcpServers`，环境引用换成 `${DATAGUARD_AGENT_TOKEN}`。选择 Agent 模式并调用工具，查看 MCP server output。工作区配置不可包含明文 Agent Token。

### Claude / Claude Desktop

Claude 的 **远程 MCP 自定义连接器** 在 `Customize > Connectors > Add custom connector` 中添加公网 URL；使用 `Request headers` 配置固定 Bearer Token、选择 `No sign in`（而不是 OAuth）。连接由 Anthropic 云端发起，必须从云端可以访问公网 TLS 服务。Claude Desktop 本地 stdio 配置不是远程 HTTP 配置的替代品。

### ChatGPT

在支持创建自定义 MCP 插件的工作区中进入 `Plugins > Add custom MCP server`，输入公网 HTTPS URL 并选择允许的认证方式。当前 DataGuard 未提供 OAuth，**不可据此宣称 ChatGPT 托管云端接入已通过**。是否允许静态凭证方式，取决于对应工作区 UI/策略。ChatGPT Business/Enterprise/Edu 自定义插件功能还需账号拥有的管理权限。

## 验收矩阵与复测方法

| 层级 | 项目 | 当前测试结论 |
|---|---|---|
| 标准 HTTP | initialize / tools/list / tools/call | 官方 TypeScript SDK v1.32.2 在隔离开发环境通过 |
| 标准 HTTP | GET 不提供 SSE 405、notification 202 | 隔离端口通过 |
| 标准 HTTP | Origin 403、无效协议头 400 | 隔离端口通过 |
| 数据业务 | MySQL COUNT、字段权限、真实数据 | 隔离开发环境通过 |
| SaaS 客户端 | Cursor GUI 的 Agent 调用 | 尚未完成，开发机没有 Cursor |
| SaaS 客户端 | VS Code + Copilot GUI 的 Agent 调用 | 尚未完成，开发机没有 VS Code |
| SaaS 客户端 | Claude / Claude Desktop 云端连接器 | 尚未完成，需用户 Claude 账号连接授权 |
| SaaS 客户端 | ChatGPT 自定义 MCP 插件云端调用 | 尚未完成，需具备权限的 ChatGPT 工作区 |

验收时应使用**独立低权限临时 Agent**，验证 tool discovery → schema → validate → execute → approval → reject denied SQL，然后注销 Agent 并检查审计，无需向供应商或聊天窗口泄露长期生产 Token。

官方规范：<https://modelcontextprotocol.io/specification/2025-11-25/basic/transports>  
Cursor：<https://cursor.com/docs/mcp>  
VS Code：<https://code.visualstudio.com/docs/agents/reference/mcp-configuration>  
Claude：<https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp>  
ChatGPT：<https://help.openai.com/en/articles/12584461-connecting-custom-mcp-servers-in-chatgpt>