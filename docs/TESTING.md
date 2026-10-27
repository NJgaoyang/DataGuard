# 测试、验收与已知边界

本文以当前 `main` 的代码和现有自动化测试为依据，**不把历史开发机某次测试的行数、请求数量或临时测试 Agent 当作长期有效的生产指标**。

## 1. CI 持续验证

工作流：`.github/workflows/ci.yml`，在主分支和 pull request 上运行。

| 步骤 | 覆盖 |
|---|---|
| Vue 构建 | TypeScript/Vite 打包 |
| Maven `clean verify` | 后端单元与 MySQL 集成测试，Flyway 迁移、鉴权和审批链路 |
| Playwright / Chromium | 管理控制台列表、详情、操作区、筛选与浏览器行为 |
| 测试产物 | 浏览器回归输出和截图（由 GitHub Actions 保存） |

推荐本地运行：

```bash
mvn -B -ntp clean verify
cd frontend
npm ci
npm run build
npx playwright install chromium
npx playwright test
```

Maven 集成测试依赖测试 MySQL 和运行环境变量；参考工作流中的 MySQL 8.4 service、`DG_MYSQL_URL`、`DG_MYSQL_USER`、`DG_MYSQL_PASSWORD`、`DG_ENCRYPTION_KEY`、`DG_INITIAL_ADMIN_PASSWORD` 的**仅测试专用值**。不要在生产数据库运行可改变数据的集成测试。

## 2. 主要后端测试类

| 测试类 | 主要业务 |
|---|---|
| `SqlQueryIntegrationTest` | 受限 SQL、聚合、两表 JOIN、授权、审计 |
| `SqlAuditTemplateTest` | 字符串/数字字面量脱敏、绑定参数不进审计 |
| `GatewayMysqlIntegrationTest` | 真实 MySQL JDBC、请求/规则/审批 |
| `McpApprovalFlowIntegrationTest` | MCP 请求、工具协议与审批行为 |
| `AgentLifecycleIntegrationTest` | 启用/停用、禁止运行中修改授权、软删除、历史保留与 Token 重签 |
| `ApprovalScopesIntegrationTest` | 管理员、审批人、普通用户可见申请范围，筛选选项权限 |
| `RequestPageIntegrationTest` | 访问记录服务端分页与身份权限 |
| `AgentConcurrentQuotaIntegrationTest` | 同一 Agent 并发分钟额度一致性 |
| `SourceConnectionSecurityTest` | 数据源连接与密码/主机安全 |
| `OperationalSecurityTest` | 密码、安全设置与操作保护 |
| `CsrfLoginIntegrationTest` | 登录 Cookie、CSRF 与角色约束 |
| `PolicyEngineTest` | 规则与请求指纹 / 审批流程 |

请以当前 CI 结论为最终可信状态；历史版本通过不意味着新提交的相同行为自动通过。

## 3. 前端关键回归场景

`frontend/tests/console.spec.ts` 对管理界面进行 Playwright 验收，包括：

- Agent 只显示“启用/已停用”，启用时编辑/删除禁用，停用后可进行操作。
- 审批中心四种筛选（全部申请/待我审批/我发起的/已处理）按权限返回正确结果；快速切换不被旧响应覆盖。
- 访问记录按日期默认当天，Agent/数据源候选项不随当前选中条件消失。
- 修改筛选和点击“重置”不自动请求；点击“查询”才应用；翻页沿用已应用条件。
- 查询 SQL 脱敏摘要和详情、历史 SQL 为空时明确提示。
- 数据源、Agent、规则及最近访问列表序号，翻页持续递增。

## 4. 已验证的能力与不能宣称的事项

| 范围 | 有过的验证 / 当前证据边界 |
|---|---|
| MySQL 与 StarRocks | 开发机使用隔离演示库进行过真实 JDBC 只读查询与审批联调，部分日期较早；新版本必须按本节重新回归 |
| 官方 MCP TypeScript SDK | 已在隔离环境真实执行 initialize、六工具发现和授权 SQL；标准 HTTP 返回码与多个协议版本曾完成验证 |
| SQL / 权限 / 审批 | Maven/MySQL 集成测试和公网回归脚本曾验证；验证针对具体代码提交，不等于自动覆盖全部生产业务数据 |
| Agent 两状态管理 | 生命周期回归包含停用后授权编辑、软删除、历史数据保留与 Token 重签 |
| Cursor / VS Code / WorkBuddy | 本仓库可以给出配置与协议兼容说明，但**没有这些 GUI 客户端的持久化账号级验收证明** |
| Claude / ChatGPT 云端 | 需要真实账号、工作区授权及可能的 OAuth；尚不能宣称真实接入成功 |
| 生产高可用与异地恢复 | 无足够证据，仍待实现及演练 |
| 任意 SQL、跨源 JOIN、大规模压测 | 本产品不支持任意 SQL/跨源 JOIN；生产压测和资源治理尚待完成 |

## 5. 提交前验收清单

1. `README.md` 和 `docs/` 与代码使用相同的接口/工具名和状态模型。
2. 如果修改 SQL AST、函数授权、审批指纹，验证非法语句、参数值、并发审批及撤权后拒绝结果。
3. 如果修改 Agent Token 或状态，验证停用/启用、Token 轮换、已删除身份拒绝以及历史数据不被清除。
4. 如果修改筛选和页面交互，验证日期/时区、重置不查询、下拉候选项切换、快速请求覆盖和每页序号。
5. 涉及 Flyway 迁移，先备份真实元数据库，在测试 MySQL 上从零安装与升级已有数据两条路径均验证。
6. 发布与回滚必须记录确切 Git SHA、JAR SHA256、Flyway 版本，验证 HTTPS、MCP 和 REST 安全链路。
7. 客户端专项验收需明确客户端版本、独立 Agent 身份、认证方式、真实测试日志和撤销凭据后的结果。

历史阶段性验收报告由于不再准确描述当前代码，已经从正式 `docs/` 导航中删除；如需追溯可使用 Git 历史记录。不要根据过期测试文档修改今天的 API。
