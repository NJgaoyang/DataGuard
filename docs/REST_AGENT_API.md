# DataGuard Agent REST — 唯一 SQL 查询接口

MCP 和 REST 分别调用统一的授权目录服务、SQL 安全查询服务和审批服务，不调用彼此的协议处理器。

基础地址：https://110.42.132.44/api/v1/agent
认证头：Authorization: Bearer <Agent Token>

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | /datasources | 已授权数据源 |
| GET | /datasources/{id}/tables | 已授权表 |
| GET | /datasources/{id}/tables/{table}/columns | 有权限的字段及类型、掩码、主键信息 |
| POST | /queries/validate | 验证只读 SQL，不查询业务数据 |
| POST | /queries | 执行 SQL SELECT |
| GET | /requests/{id} | 查询审批状态 |

POST /queries 请求体：
```json
{"sourceId":"UUID","sql":"SELECT COUNT(*) AS total FROM orders LIMIT 1","params":[]}
```

支持在同一数据源上查询、聚合、计算、GROUP BY/HAVING/ORDER BY、LIMIT/OFFSET 和授权的双表 JOIN。
后端 AST 校验所有被引用表与字段；动态 SQL 不允许掩码字段。无法执行写操作、跨数据源 JOIN、任意子查询、CROSS JOIN 或危险函数。
请求参数通过 JDBC PreparedStatement 绑定；使用只读账号、最大行数、查询超时、并发限制、查询审批及审计。
需要审批时 REST 返回 HTTP 202 与 status=PENDING、requestId，需等待真实审核并用完全相同的请求重试一次。
异常返回 4xx/5xx + status=ERROR、code 和 message。Token 不得写进 URL 或仓库。

管理员安全函数配置：GET/PUT /api/sql/functions。