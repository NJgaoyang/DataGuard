# MCP 接入、工具与互操作性

DataGuard 的 `/mcp` 使用 **MCP 官方 Java SDK 0.18.2**、Spring WebMVC、**Stateless Streamable HTTP**；与 REST 共享 SQL 权限、人工审批、额度和审计服务。旧的自制 JSON-RPC 控制器及 `execute_readonly` / `count_rows` / `aggregate_readonly` 不再存在。

## 服务端真实能力

| 项目 | 实现 |
|---|---|
| 入口 | `POST /mcp`，JSON-RPC 2.0，支持标准 `initialize` / `tools/list` / `tools/call` / `ping` |
| 版本 | `2025-03-26`、`2025-06-18`、`2025-11-25` |
| 成功通知 | 不含请求 ID 的 notifications 返回 HTTP **202** |
| SSE | 无服务器推送事件；`GET /mcp` 返回 HTTP **405**（正常，无需修复） |
| Session | 无状态；不分配 `Mcp-Session-Id`，不支持 session resume |
| 身份 | `Authorization: Bearer <AGENT_TOKEN>`，仅有效、启用且具有授权的 Agent 可操作 |
| Origin | 如果请求携带 `Origin`，必须在 `DG_ALLOWED_MCP_ORIGINS` 中显式允许；无 Origin 的正常桌面/服务端连接无需该项 |
| HTTP 错误 | 无效/缺失凭据 401；显式不可信 Origin 403；不支持的版本头 400；额度超过限制 429 |
| OAuth | **未实现 OAuth 2.1** 授权服务器或浏览器登录重定向，不能声称支持所有需要 OAuth 的云端客户端 |
| 工具特性 | `tools/list` 静态工具集；不提供工具动态变更推送、采样或反向请求 |

客户端请求应包含合适的 `Accept: application/json, text/event-stream`，官方 MCP SDK 会按协议处理初始化与通知。直接发 `tools/list` 但跳过初始化，不能代替客户端互操作性测试。

## 六个 MCP 工具

| 工具 | 必填参数 | 功能 |
|---|---|---|
| `list_datasources` | 无 | 当前 Agent 可见的数据源 |
| `get_schema` | `sourceId` | 列出授权表 |
| `describe_table` | `sourceId`、`tableName` | 读取被授权的列结构 |
| `validate_sql` | `sourceId`、`sql` | 检查 SQL 语法、函数、表/字段授权与限制，不执行查询 |
| `execute_sql_readonly` | `sourceId`、`sql`；可选 `params` | 执行经授权的只读 SELECT，必要时请求人工审批 |
| `get_access_request` | `requestId` | 获取本人关联的审批状态 |

`params` 是数组，元素只能是标量（字符串、数字、布尔或 null），对应 SQL 中的 `?` 占位符。SQL 引擎可查询计算表达式、聚合及同源两表 JOIN，细则见 [SQL_QUERY.md](SQL_QUERY.md)。

## 通用连接参数

- URL（开发环境）：`https://110.42.132.44/mcp`
- 传输：`Streamable HTTP`（**不是旧 SSE**）
- HTTP Header：`Authorization: Bearer <AGENT_TOKEN>`
- 使用 DataGuard 创建的**独立、低权限 Agent**，不要把后台管理员密码当成 Token。
- 需要公网访问的客户端必须能正确验证 HTTPS 证书；禁止为解决握手问题而长期关闭 TLS 验证。

## 常见客户端配置

下面是配置**范例**，不同产品/版本设置名称可能调整。除官方 SDK 测试之外，**不代表这些产品已在你的账号里实际完成端到端验收**。

### Cursor

用户级 `~/.cursor/mcp.json`（放在本机私有配置，不要提交仓库）：

```json
{
  "mcpServers": {
    "dataguard": {
      "url": "https://110.42.132.44/mcp",
      "headers": {
        "Authorization": "Bearer <AGENT_TOKEN>"
      }
    }
  }
}
```

连接后先调用 `list_datasources`，再尝试 `validate_sql` 和 `execute_sql_readonly`。确认编辑器启用了该 MCP 服务器，并在 MCP 日志中查看工具列表。Token 应使用客户端支持的安全凭据配置，不应出现在工作区公共文件中。

### VS Code / GitHub Copilot

VS Code Profile MCP 配置或本机私有 `.vscode/mcp.json`：

```json
{
  "servers": {
    "dataguard": {
      "type": "http",
      "url": "https://110.42.132.44/mcp",
      "headers": {
        "Authorization": "Bearer ${env:DATAGUARD_AGENT_TOKEN}"
      }
    }
  }
}
```

在运行 VS Code 的环境中设置 `DATAGUARD_AGENT_TOKEN`，然后启用 MCP 服务器、在 Agent 模式验证工具可调用。不要把明文 Token 提交到仓库。

### WorkBuddy

在 WorkBuddy 的 MCP 服务配置中加入远程 HTTP 项；不同版本字段命名/环境变量替换方式应按客户端实际支持情况确认。例如：

```json
{
  "mcpServers": {
    "dataguard": {
      "type": "streamableHttp",
      "url": "https://110.42.132.44/mcp",
      "headers": {
        "Authorization": "Bearer <AGENT_TOKEN>"
      }
    }
  }
}
```

保存并启用后，先用自然语言要求 WorkBuddy 调用 `list_datasources`，再让它查看表结构和执行授权 SQL。**是否已真正连上需要检查客户端调用日志，不能仅凭 JSON 合法认定通过**。不同版本可能要求其他配置位置或字段。

### Claude / Claude Desktop

远程 MCP 应通过相应客户端提供的 **自定义远程连接器** 配置 URL。是否支持静态自定义 Header/No sign-in 以及连接器是否由云端访问，取决于产品版本和账户策略；若要求 OAuth，则当前 DataGuard 的静态 Token 不能直接替代 OAuth 流程。不要误将 Claude Desktop 本地 stdio 配置视为远程 HTTP 已验证。

### ChatGPT

在具有**自定义 MCP 应用/服务器**权限的工作区中配置 HTTPS 远程服务器。受账号方案、插件策略和支持的授权方式限制；当前 DataGuard 不提供 OAuth，**不保证云端 ChatGPT 可以使用静态 Bearer 直接连接**。此项尚缺真实账号级操作证据。

## 建议的真实验收步骤

1. 为每一种客户端创建一个独立低权限 Agent，授权仅一张演示表，保管一次性 Token。
2. 初始化连接并记录协议版本，核对六个工具是否全部可见。
3. `get_schema`、`describe_table` 只应显示获授权的表/字段。
4. `validate_sql` 对合法只读 SQL 成功，越权列、危险函数、DDL/DML 必须拒绝。
5. `execute_sql_readonly` 成功返回少量授权测试数据。
6. 切换规则触发 `PENDING`，由其他用户审批，查询 `get_access_request`，原参数重试并确认单次批准不可二次消费。
7. 在 DataGuard「访问记录」核对 Agent 名、脱敏 SQL 模板和审计；停用或轮换 Token 后原凭据不得再使用。

### 已有测试与未完成事项

- **已完成**：隔离环境官方 TypeScript SDK 真实初始化、工具发现、MySQL 查询及标准 HTTP 边界测试；公网曾执行标准 MCP、SQL、JOIN、审批等集成回归，具体版本以 CI 记录为准。
- **未以真实账号完成**：Cursor GUI、VS Code/Copilot GUI、WorkBuddy GUI、Claude 远程连接器、ChatGPT 自定义应用。若需要把其中一项标成“完成”，必须附客户端连接证据及成功审计记录。
- **未实现**：OAuth 2.1 授权服务、SSE 推送及会话恢复。

## 常见问题

| 现象 | 排查方向 |
|---|---|
| `401` | Bearer Token 未携带、被轮换、过期、Agent 停用或软删除 |
| `403` | 原因可能是 Origin 未允许、表/字段授权不足、规则拒绝；查看 HTTP 请求上下文 |
| `405 GET /mcp` | **正常**：该服务器不提供服务器到客户端的 SSE 流，客户端应走 POST Streamable HTTP |
| `400` | 协议版本头、SQL 输入参数或 JSON-RPC 内容无效 |
| `PENDING` | 需要独立审批人通过；之后按完全一致的 SQL/参数重试 |
| 工具列表为空 | 检查连接器传输类型、Header、是否真正完成 initialize、是否启用了服务器 |

参考协议：[Model Context Protocol](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)。