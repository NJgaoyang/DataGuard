# REST Agent API v1

MCP 与 REST 共用 Agent Bearer Token、五个 MCP 工具实现以及既有 SQL 授权、审批、审计和限流。调用方不得使用管理后台会话代替 Agent Token。

入口：`https://110.42.132.44/api/v1/agent`。使用 HTTPS，令牌通过 `Authorization: Bearer dg_...` 发送。所有路径需要有效未停用、未撤销、未过期的 Agent Token。

| 请求 | 路径 | 对应 MCP 工具 |
|---|---|---|
| GET | `/datasources` | list_datasources |
| GET | `/datasources/{id}/tables` | get_schema |
| GET | `/datasources/{id}/tables/{table}/columns` | describe_table |
| POST | `/queries` | execute_readonly |
| GET | `/requests/{id}` | get_access_request |
| POST | `/counts` | count_rows |
| POST | `/aggregations` | aggregate_readonly |
| GET | `/datasources/{id}/tables/{table}/metadata` | describe_table（返回类型/主键/可空/脱敏） |

请求示例：

```bash
curl -X POST 'https://110.42.132.44/api/v1/agent/queries' \
  -H 'Authorization: Bearer dg_REPLACE_ME' \
  -H 'Content-Type: application/json' \
  -d '{"sourceId":"UUID","tableName":"orders","columns":["order_id","amount"],"limit":10}'
```

SQL 由后端生成，只允许读取显式授权的字段，不接受任意 SQL。单次行数受安全设置 `max_rows_per_query` 控制，默认 200 行；允许配置更高，但不得超过分钟级行数额度。命中审批时返回 `status=PENDING` 和 `requestId`，等待审批后提交完全相同的请求。REST 的数据格式是 JSON 而非 MCP text-content 封装。

安全：查询限制、审批、审计和数据脱敏沿用 MCP。不要将 Token 写进 Git 或 URL。REST 的调用方必须通过有效 TLS 校验证书。

## 统计、聚合与分页

`POST /counts` 可按授权表统计总行数，返回 `total`；默认不返回原始业务数据。示例：

```json
{"sourceId":"UUID","tableName":"orders"}
```

`POST /aggregations` 支持 `COUNT`、`SUM`、`AVG`、`MIN`、`MAX`，可按 `groupColumn` 分组：

```json
{"sourceId":"UUID","tableName":"orders","aggregateFunction":"SUM",
 "aggregateColumn":"amount","groupColumn":"status","limit":20,"offset":0}
```

记录查询 `POST /queries` 支持 `limit` 与 `offset`。偏移量区间为 0～1000000，分页时尽量同时传入 `sortColumn` 和 `sortDirection` 保持稳定排序。

聚合列、分组列、过滤列须具备平台和 Agent 的有效授权，不允许对脱敏字段分组、筛选或聚合。以上操作均执行规则引擎、审批、审计及查询超时控制，不提供任意 SQL。

需要审批时 REST 返回 **HTTP 202** 和 `{"status":"PENDING","requestId":"..."}`；调用方通过 `GET /requests/{id}` 轮询，审批通过后重试相同的参数一次。失败统一返回 `{"status":"ERROR","code":"...","message":"..."}`，并使用对应的 HTTP 4xx/5xx 状态码；429 带有 `Retry-After`。

MCP 使用 2025-03-26、2025-06-18 或 2025-11-25 版本的 **JSON 响应模式 Streamable HTTP**，不承诺 2026-07-28 现代协议时代、SSE 实时推送或旧 HTTP+SSE 兼容。需要在正式客户端中验证目标版本。