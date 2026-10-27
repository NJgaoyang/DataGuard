# DataGuard 文档中心

本目录以 GitHub **`main`** 的实际实现为准，适用于当前 SQL 查询、六工具 MCP、Agent 两状态管理与 Flyway V1–V8。示例地址属于开发演示环境；文档既不是生产合规认证，也不承诺第三方云端客户端已完成账号级接入。

| 文档 | 内容 | 适合读者 |
|---|---|---|
| [GETTING_STARTED.md](GETTING_STARTED.md) | 依赖环境、配置项、构建启动、初始账号、首次授权与首个查询 | 开发 / 首次部署 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 组件职责、权限与执行链、审批状态、元数据、审计及边界 | 架构 / 研发 |
| [MCP_INTEROPERABILITY.md](MCP_INTEROPERABILITY.md) | 官方 Java SDK、协议版本、六工具、常见客户端配置与验证状态 | Agent / MCP 客户端 |
| [REST_AGENT_API.md](REST_AGENT_API.md) | Bearer 认证、全部外部 REST 接口、请求参数、状态、错误码 | 后端 / 集成 |
| [SQL_QUERY.md](SQL_QUERY.md) | SQL AST 规则、两表 JOIN、函数白名单、限流、审批指纹、SQL 脱敏 | 开发 / 安全 |
| [CONSOLE_GUIDE.md](CONSOLE_GUIDE.md) | 工作台、数据源、Agent、规则、审批、访问记录与筛选交互 | 管理员 / 审批人 |
| [OPERATIONS.md](OPERATIONS.md) | 构建、systemd/Caddy、监控、备份、发布回滚与生产待办 | 运维 / SRE |
| [TESTING.md](TESTING.md) | CI、真实已验收范围、没有实测的客户端、回归用例及限制 | QA / 运维 |
| [UI_DESIGN_SYSTEM.md](UI_DESIGN_SYSTEM.md) | Vue 共享组件、设计 Token、ECharts、Monaco SQL 查看器 | 前端 / UI |

## 阅读路径

新用户：**快速开始 → 控制台指南 → MCP/REST 接入**。

平台集成开发：**架构 → SQL 查询 → REST/MCP 协议 → 测试**。

运维：**快速开始 → 运维 → 测试**。

## 文档维护准则

1. 使用主分支真实接口名，禁止重新引入旧版 `execute_readonly`、`count_rows`、`aggregate_readonly` 等已经移除的查询工具。
2. SQL 引擎是受限制的只读 SELECT，不应写成“支持任意 SQL”或“不支持 JOIN”。
3. 把 **API/SDK 互操作通过** 与 **Cursor、VS Code、Claude、ChatGPT、WorkBuddy 实际客户端账号验收**分开陈述。
4. 数据库元数据、审批与审计在 MySQL；任何功能迭代涉及迁移时须说明 Flyway 版本及旧记录处理。
5. 大型功能应同步更新相关专题文档与根目录 README，避免积累多个按阶段日期命名、互相矛盾的验收文档。