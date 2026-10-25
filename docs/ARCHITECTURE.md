# DataGuard v1 独立产品架构

## 核心路径

外部 AI Agent → /mcp Bearer Token → Agent 身份校验 → 平台源/表/列白名单和 Agent 独立授权 → 运行时规则引擎 → 直接放行 / 单次人工审批 / 直接拒绝 → 仅 SELECT JDBC → 重新检查授权 → MySQL 请求与审计记录。

## 菜单与业务归属

- 工作台：24 小时访问次数、待审批、拒绝、成功率与最近请求
- 数据源：MySQL/StarRocks、表白名单、字段白名单、只读账号、启停
- Agent 管理：归属用户、令牌、有效期、Agent 独立表字段授权
- 访问规则：按数据源、Agent、表、字段和单次读取行数匹配；拒绝 > 审批 > 放行；无匹配默认拒绝
- 审批中心：待我审批、我发起的、已处理；只能由指定的真实内部审批用户操作，禁止自审
- 访问记录：按 Agent、数据库、状态、日期查询；完整请求关联；不保存业务查询结果
- 系统设置：账号、角色、强制改密与安全参数

## 数据库设计

Flyway V1 创建 9 张核心表和 1 张审计表：dg_users、dg_sources、dg_source_tables、dg_source_columns、dg_agents、dg_agent_tables、dg_agent_columns、dg_rules、dg_requests、dg_audit。

所有数据库密码经 AES-256-GCM 加密后保存；Agent Token 只保存 SHA256 摘要。运维必须在独立的安全配置中维护 DG_ENCRYPTION_KEY，不能提交仓库。

## 实时访问安全边界

- 数据源只能配置明确的只读账号，应用自身也只生成单表、已授权列的 SELECT 语句，不支持任意 SQL/DDL/DML/JOIN。
- 单次最高 200 行，最长查询 5 秒，默认最多 4 个并发 SQL。
- JDBC 使用 MySQL 驱动连接 MySQL/StarRocks，要求有效 TLS 证书和主机名验证；只允许环境变量明确允许的业务源主机。
- 申请审批时记录不可逆 SHA256 请求指纹，包含 Agent 身份、数据源、表、字段、行数、筛选值、排序条件；记录不保存筛选敏感值。
- 单次 APPROVED 授权通过行锁原子转为 EXECUTING，禁止重复消费；审批超时 24 小时自动失效。
- 禁止申请人自审；审批通过不代表突破数据库权限或白名单。
- MCP 只支持 JSON response 模式的 Streamable HTTP 2025 协议；不宣称支持 SSE/现代协议扩展。
- 目前主要为设计和代码实现基线；公网运行、正式数据库权限、浏览器端到端和生产安全验收仍需独立测试。