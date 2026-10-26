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

请求示例：

```bash
curl -X POST 'https://110.42.132.44/api/v1/agent/queries' \
  -H 'Authorization: Bearer dg_REPLACE_ME' \
  -H 'Content-Type: application/json' \
  -d '{"sourceId":"UUID","tableName":"orders","columns":["order_id","amount"],"limit":10}'
```

SQL 由后端生成，只允许读取显式授权的字段，不接受任意 SQL。单次最多 200 行。命中审批时返回 `status=PENDING` 和 `requestId`，等待审批后提交完全相同的请求。REST 的数据格式是 JSON 而非 MCP text-content 封装。

安全：查询限制、审批、审计和数据脱敏沿用 MCP。不要将 Token 写进 Git 或 URL。REST 的调用方必须通过有效 TLS 校验证书。
