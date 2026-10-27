# DataGuard SQL V2 — 安全动态 SELECT 和两表 JOIN

## 接口

MCP 工具：
- `validate_sql`：仅解析、鉴权，不执行语句、不消耗业务行数。
- `execute_sql_readonly`：读取、聚合或两表联查。审批未通过返回 `status=PENDING`（`isError=false`）。

REST：
- `POST /api/v1/agent/queries/validate`
- `POST /api/v1/agent/queries`

统一参数（Agent Bearer Token）：

```json
{
  "sourceId": "source-uuid",
  "sql": "SELECT o.order_id AS id, ROUND(SUM(i.quantity * i.unit_price),2) AS total FROM orders o LEFT JOIN order_items i ON o.order_id=i.order_id WHERE o.status=? GROUP BY o.order_id ORDER BY total DESC LIMIT 100",
  "params": ["SUCCESS"]
}
```

仅支持一条 SELECT（不含 `;`）。`params` 中的值使用 JDBC 占位符绑定，不拼接用户输入。结果包含 `status`、`rowCount`、`rows`、`requestIds`。当其中任一表需要审批时，须完成该表的独立审批；重试相同 SQL 与参数后继续。

## 当前已实现语法

- 单一 MySQL 或 StarRocks 数据源，每次最多两张不同的物理表。
- INNER / LEFT / RIGHT JOIN；FULL OUTER JOIN 仅 StarRocks。
- 至少一个用列名限定的两表等值 JOIN 条件；无条件 JOIN、CROSS JOIN、NATURAL JOIN、JOIN USING、JOIN ON ... OR 拒绝。
- SELECT 显式表达式，聚合、算术、CASE WHEN、GROUP BY、HAVING、ORDER BY、LIMIT、OFFSET。
- 单表可使用未限定字段名；两表使用 `表别名.列名`。表达式输出应指定 AS 别名。
- COUNT(*) 可用于行数统计，无需逐行读取。
- 没有 LIMIT 时自动追加 `LIMIT 100`（不超过平台设置）；限制最多使用 `max_rows_per_query`。
- UNION、WITH/CTE、窗口函数、子查询、星号列展开、跨数据源 JOIN、SELF JOIN 不在本版本范围内，均拒绝执行。
- 所有表及参与计算、WHERE、ON、GROUP BY、HAVING、ORDER BY 的字段都需平台白名单 + Agent 权限；动态 SQL 禁止使用脱敏字段（即使只用作过滤）。
- 每张 JOIN 表分别执行规则匹配、审批、执行后再次验证授权，任何一个拒绝都不返回结果。
- 限流、最大行数、查询超时、并发许可、JDBC 只读连接均生效。对于大表 JOIN，建议额外对底层数据库角色设置资源组/查询扫描限制：LIMIT 无法约束扫描成本。

## 函数可配置

Flyway V6 创建 `dg_sql_functions`，预置安全函数集。
管理员使用 `GET /api/sql/functions` 查看；`PUT /api/sql/functions` 配置：

```json
{"functionName":"RADIANS","enabled":true}
```

配置更新后，下一个查询即可使用该函数，**无需修改 Java 代码**。高风险函数（例如 SLEEP、BENCHMARK、LOAD_FILE、GET_LOCK）始终禁止，配置也无法启用。此接口仅允许 ADMIN 使用。新增函数前仍需评审函数的副作用及不同数据库方言差异，函数白名单不等于允许自定义 UDF。

## 兼容及审计

旧固定查询工具与单表 REST 端点已移除，SQL 查询是唯一执行入口。新入口新增 SQL 参数指纹、独立表级审批和审计，所有结果通过相同的 Agent 鉴权。SQL 查询及其参数不写入明文审计记录。

当前 V2 不声称具备联邦查询、任意 SQL 或完全 SQL-92 语法覆盖。数据库物理数据仅使用独立只读账户查询，不创建或改写业务表。