# P0 真实业务链路验收（开发机）
> 该文档仅包含非敏感测试结论。演示账号密码和 Agent Token 仅存储在开发机 root-only 配置下，未提交 Git。
 
## 验收前提
- 演示专用数据库：MySQL 8.4 与 StarRocks 3.5 的独立 `dg_p0_demo`，仅包含 3 行合成测试数据。
- 读取账号：两个库独立的 `dg_p0_demo_ro`，只授予指定表 `SELECT`，不开放 DDL/DML。
- 系统内部用户：`p0_demo_owner`（READER）与 `p0_demo_reviewer`（APPROVER），各自有独立口令。
- 公网 MCP：`https://110.42.132.44/mcp`，HTTPS 校验证书，使用 Bearer Agent Token。
- 演示账号与授权作用域只针对 `dg_p0_demo.orders`；不会访问或修改已有业务库。
 
## 实测结果
| 验收项 | 结果 |
| --- | --- |
| 平台连接 MySQL 只读数据源 | 通过 |
| 平台连接 StarRocks 只读数据源 | 通过 |
| MCP initialize / tools/list（5 工具） | 通过 |
| Agent 仅看到明确授权的数据源/表/字段 | 通过 |
| MySQL 真实 JDBC SELECT | 通过，返回 3 行 |
| StarRocks 真实 JDBC SELECT | 通过，返回 3 行 |
| 未授权字段被拒绝 | 通过 |
| MySQL 申请产生 PENDING | 通过 |
| 非审批人员审批被拒绝 | 通过 |
| 独立 APPROVER 登录后批准 | 通过 |
| Agent 轮询 APPROVED、原请求重试成功 | 通过 |
| 单次审批不可重复使用 | 通过；再次调用产生新申请 |
| 请求记录和操作审计 | 通过 |
| 临时演示初始化管理员已停用 | 通过 |
 
## 范围与待完成
- 已进行真实 HTTPS、Spring Boot、MySQL/StarRocks JDBC 的完整业务查询和审批验证，非 Mock。
- 未将 StarRocks 演示实例的明文 loopback 连接配置当作正式环境 TLS 验收。
- 尚需独立外部 MCP SDK/Agent 应用兼容性认证，当前使用真实 JSON-RPC HTTP 客户端。
- 仍需权限撤销竞态、故障恢复、压力测试、限流、审计防篡改和正式生产角色验收。
- 禁止将凭据或 Agent Token 写入本仓库；开发机凭据保存在 `/etc/dataguard-new/p0-demo/credentials.json`，仅 root 可读。
 
最近成功审批请求：`44456df8-15fe-4a40-a768-3570582898b8`。
