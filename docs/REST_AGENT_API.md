# DataGuard Agent REST API

REST 与 MCP 使用相同的 Agent 身份、授权目录、SQL 安全引擎、审批和审计。**下表是目前唯一的外部 Agent REST SQL 查询接口**；旧的固定 `READ/COUNT/AGGREGATE` 查询入口已移除。

## 认证与 URL

- 测试站地址：`https://110.42.132.44/api/v1/agent`（替换为自己部署的 HTTPS 域名）
- Header：`Authorization: Bearer <AGENT_TOKEN>`，创建 Agent 时签发
- 认证：独立于管理后台 Cookie；Token 必须有效、Agent 启用、所属用户有效
- 只能查询当前 Agent 授权的源、表、列；访问规则仍可能 `DENY` 或触发 `APPROVAL`
- `Content-Type: application/json`；不要将 Token 放到 URL 或源代码仓库

## API 路由

| 方法 | 路径（基于 `/api/v1/agent`） | 说明 |
|---|---|---|
| GET | `/datasources` | 当前 Agent 授权的数据源 |
| GET | `/datasources/{id}/tables` | 当前 Agent 在数据源下的授权表 |
| GET | `/datasources/{id}/tables/{table}/columns` | 当前 Agent 授权列的信息 |
| POST | `/queries/validate` | 解析和鉴权 SQL，不执行业务源查询 |
| POST | `/queries` | 执行已授权的只读 SELECT |
| GET | `/requests/{id}` | 查询当前 Agent 的申请状态 |

注意路由与 MCP 工具不同；管理后台另有 `/api/agents`、`/api/rules`、`/api/requests/page` 等接口，这些使用**登录会话 + CSRF**，不是 Agent Bearer 接口。

## SQL 请求格式

```json
{
  "sourceId": "数据源 UUID",
  "sql": "SELECT o.order_id AS id, SUM(i.quantity * i.unit_price) AS amount FROM orders o INNER JOIN order_items i ON o.order_id = i.order_id WHERE o.status = ? GROUP BY o.order_id ORDER BY amount DESC LIMIT 50",
  "params": ["SUCCESS"]
}
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `sourceId` | 是 | DataGuard 数据源 UUID |
| `sql` | 是 | 一条受限 SELECT；SQL 长度最多 6000 字符 |
| `params` | 否 | 与 `?` 一一对应的标量数组，最多 40 个；字符串单值最长 1024 |

SQL 不得附加多条语句，不能写 DML/DDL 或调用高风险函数。没有 LIMIT 时自动补上最大 100（并受配置上限约束）；不允许 `SELECT *` 展开，`COUNT(*)` 除外。返回集合受平台单次最大行数、并发和读取额度限制。

### SQL 校验

```bash
curl -X POST "$DATAGUARD_URL/api/v1/agent/queries/validate" \
 -H "Authorization: Bearer $DATAGUARD_TOKEN" \
 -H 'Content-Type: application/json' \
 -d '{"sourceId":"数据源 UUID","sql":"SELECT COUNT(*) AS total FROM orders LIMIT 1","params":[]}'
```

校验返回结构包含 SQL 规划和限制信息，不触发 JDBC 业务查询，也不能替代真正的规则审批。

### 实际执行

```bash
curl -X POST "$DATAGUARD_URL/api/v1/agent/queries" \
 -H "Authorization: Bearer $DATAGUARD_TOKEN" \
 -H 'Content-Type: application/json' \
 -d '{"sourceId":"数据源 UUID","sql":"SELECT COUNT(*) AS total FROM orders LIMIT 1","params":[]}'
```

成功响应包含 `status`、`rowCount`、`rows`、`requestIds` 等字段。两表 JOIN 可能产生两个请求 ID；每张表必须分别满足独立规则和授权。

### 需要人工审批时

HTTP **202**，响应示意：

```json
{
  "status": "PENDING",
  "requestId": "待审申请 UUID",
  "message": "Approval required; retry identical SQL and params after approval"
}
```

调用 `GET /requests/{id}` 查询状态，由**不同于申请人的授权审批人**在管理后台批准或拒绝。审批通过只允许对**完全相同的 SQL、参数、Agent 和相关规则权限**执行一次；切换条件可能生成新的审批请求。

### 错误处理

REST 统一返回 HTTP 4xx/5xx，响应包括 `status: ERROR`、`code`、`message`。常见：

| HTTP | 代表性 code | 排查 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 非法/超额 SQL、参数数量、JOIN 或函数 |
| 401 | `UNAUTHORIZED` | Agent Token 缺失、失效、停用、到期 |
| 403 | `ACCESS_DENIED` | 表列权限不足、规则不允许 |
| 404 | `NOT_FOUND` | 源、表或申请不可见 |
| 409 | `CONFLICT` | 状态变化、审批使用冲突 |
| 429 | `RATE_LIMITED` | 分钟额度或 SQL 并发限制；检查 `Retry-After` |
| 502 | `DATASOURCE_FAILURE` | 业务源连接/读取失败 |

## 管理端（区别于 Agent REST）

管理后台使用浏览器会话与 CSRF，主要接口：

- `GET /api/requests?scope=all|pending|mine|handled`：审批中心
- `GET /api/requests/filters`：有权查看的完整 Agent/数据源筛选选项
- `GET /api/requests/page?page=1&status=...&agent=...&source=...&from=YYYY-MM-DD&to=YYYY-MM-DD&search=...`：访问记录，服务端每页 10 条
- `GET /api/requests/{id}`：访问详情，含 `sqlTemplate`
- `POST /api/requests/{id}/approve|reject`：审批决策，需要有权限的后台用户与理由
- `GET/PUT /api/sql/functions`：管理员安全 SQL 函数开关

不要从 Agent 脚本中拿管理 Cookie 或 CSRF 去调用这些接口；请按职责使用正式 Agent REST 接口。

更多限制：[SQL 查询](SQL_QUERY.md) · [MCP](MCP_INTEROPERABILITY.md) · [后台](CONSOLE_GUIDE.md)。