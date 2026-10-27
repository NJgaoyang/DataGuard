# SQL 查询引擎与安全规则

DataGuard 不是通用数据库 SQL 代理。Agent 只能提交**严格受限的只读 SELECT**，每个参与表和字段均需平台 + Agent 授权，并匹配访问规则。MCP `execute_sql_readonly` 和 REST `POST /api/v1/agent/queries` 均调用 `SqlQueryService`。

## 1. 统一参数

```json
{
  "sourceId": "数据源 UUID",
  "sql": "SELECT o.order_id AS order_id, ROUND(SUM(i.quantity * i.unit_price), 2) AS total FROM orders o LEFT JOIN order_items i ON o.order_id=i.order_id WHERE o.status=? GROUP BY o.order_id ORDER BY total DESC LIMIT 50",
  "params": ["SUCCESS"]
}
```

- `sourceId` 必填；`sql` 必填且长度不超过 6000 字符。
- `params` 可选，最多 40 个标量，对应 SQL 的 `?`。参数通过 JDBC PreparedStatement **绑定，不与 SQL 字符串拼接**。
- `validate_sql` 和 REST `/queries/validate` 解析并检查授权，不执行实际业务源 SQL。
- SQL 语句通过 JSQLParser AST 检查后才允许进入规则与数据库执行流程；不依赖简单关键词筛选作为唯一安全控制。

## 2. 已支持

| 语法 | 条件 |
|---|---|
| `SELECT` 显式列与表达式 | 字段需授权；表达式结果建议使用 `AS` 别名 |
| `COUNT(*)`、`SUM`、`AVG` 等聚合 | `COUNT(*)` 是聚合参数特例，不代表放行 `SELECT *` |
| 算术、CASE、白名单函数 | 函数必须在 `dg_sql_functions` 中启用，永久危险函数无法启用 |
| `WHERE / GROUP BY / HAVING / ORDER BY` | 参与判断、排序和计算的字段也必须经过授权 |
| `LIMIT / OFFSET` | 未写 LIMIT 自动加至多 100 行（受平台上限影响）；显式值必须在上限内 |
| 同一数据源最多两张不同物理表 | `INNER/LEFT/RIGHT JOIN`，StarRocks 可用 `FULL OUTER JOIN` |
| 两表 JOIN | 必须有正确的跨表列等值条件，按每张表独立申请/审核 |

单表可以使用未限定字段；两表 JOIN 应使用表名或别名限定字段名。两个表必须同一 DataGuard 数据源，跨数据源查询不支持。

## 3. 明确不支持

- DDL/DML（`CREATE / DROP / ALTER / INSERT / UPDATE / DELETE`）与多语句、写入文件/锁定操作。
- `SELECT *` 全列投影、任意 UNION、WITH/CTE、递归语句、子查询/派生表、窗口函数。
- `CROSS JOIN`、`NATURAL JOIN`、`JOIN USING`、没有跨表等值条件的 JOIN、SELF JOIN、跨源 JOIN。
- 高危数据库函数、任意 UDF 或通过系统元信息函数绕过数据隔离。
- 自动跨数据库分布式 JOIN、全 SQL-92/SQL-2011 方言覆盖。

规则外的查询按默认拒绝处理；“语法受支持”不意味着 Agent 自动拥有表、字段或执行权限。

## 4. 安全顺序

1. **Agent 验证**：Token、所属账号、运行状态、过期和软删除状态。
2. **SQL AST 解析**：单条 SELECT、禁止的结构、显式字段/函数和参数匹配。
3. **授权交集**：DataGuard 数据源启用、平台表/字段白名单、Agent 表/字段授权以及字段掩码限制。
4. **每张表匹配规则**：优先级 `DENY` ＞ `APPROVAL` ＞ `ALLOW`；无匹配规则拒绝。JOIN 两表各有独立请求记录。
5. **审批**：需要时返回 `PENDING`、`requestId`；审批必须由独立的合规账号决定，且一次批准只能消费一次。
6. **资源限制**：行数/分钟额度、并发许可、JDBC 语句超时、物理只读账号与主机白名单。
7. **执行后再检查**：规则修改、撤权和执行期间状态变化，不可借先通过校验绕开返回时的安全判断。
8. **审计**：记录请求结果、行数、耗时、规则、审批及安全事件；不保存查询结果。

审批指纹包含与 Agent、数据源、SQL、绑定参数及授权范围相关的稳定信息，审批通过须**相同请求重试**。调整 SQL 文本、参数值或部分规则可能意味着新申请。

## 5. 资源控制与函数管理

`GET/PUT /api/sql/functions` 仅管理员可管理安全函数。Flyway V6 初始化允许的函数集合；某个函数未列入安全集合时，不因为表达式合法就允许执行。

`GET /api/security/settings` 和 `PUT /api/security/settings` 由管理员控制（需要后台会话和 CSRF）：

- `maxRowsPerQuery` 默认 200，可调整，但配置上限和每分钟预算由后端校验。
- `agentCallsPerMinute` 默认 60；`agentRowsPerMinute` 默认 3000。
- `SqlQueryService` 默认语句超时 5 秒，并发默认最多 4。
- 没有 `LIMIT` 的 SELECT 自动补 `LIMIT min(100, 当前配置上限)`。
- 这些只约束调用、计划返回量和语句时间，**不保证大表扫描、JOIN 内存、计算成本上限**，请在源数据库另设查询资源隔离、只读角色和超时。

## 6. SQL 审计模板（Flyway V8）

V8 为 `dg_requests` 增加 `sql_template TEXT NULL`，保存**通过 SQL 解析/授权后用于展示的脱敏 SQL 结构**，不保存原始敏感字面量或 JDBC 绑定值。

例如原查询：

```sql
SELECT order_id FROM orders WHERE status='PRIVATE' AND amount>123.45 LIMIT 20
```

访问记录中显示的模板会以 `'[REDACTED]'` 和 `?` 遮蔽字符串、数字：

```sql
SELECT order_id FROM orders WHERE status='[REDACTED]' AND amount>? LIMIT ?
```

真实 JDBC 执行语句仍由 SQL AST 计划及绑定参数决定；**日志模板不是再次提交给数据库执行的 SQL**。JOIN 的每张表各有请求记录和对应模板。旧记录 SQL 从未存储，不可能补全，界面显示“历史记录未保存 SQL”。

> 脱敏模板虽然遮蔽了参数和字面量，仍包含表、列、查询结构，属于敏感运维信息。访问记录权限控制必须继续生效。运行前被 SQL AST 拒绝的非法语句不会生成一条成功的业务查询记录。

## 7. 实现与测试

- 业务服务：`SqlQueryService`、`PolicyEngine`、`BusinessReadExecutor`、`SqlFunctionApi`、`SqlAuditTemplate`。
- 主要测试：`SqlQueryIntegrationTest`、`SqlAuditTemplateTest`、`GatewayMysqlIntegrationTest`、`McpApprovalFlowIntegrationTest`。
- 入口：[REST_API](REST_AGENT_API.md) · [MCP](MCP_INTEROPERABILITY.md)；架构说明：[ARCHITECTURE.md](ARCHITECTURE.md)。