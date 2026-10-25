# MCP DataGuard

**独立项目**：基于新的产品定义从零开发，面向 AI Agent 的数据库受控读取、访问规则、人工审批与审计。与旧版 `MCP-DataGuard` 仓库分离。开发分支 `feature/ai-access-gateway-v1`。

技术栈：Java 21、Spring Boot 3.5、MySQL 8.4、JDBC、Vue 3、Ant Design Vue；单体 JAR + systemd，不依赖 Docker 部署。业务读取支持 MySQL 与 StarRocks 的 MySQL 兼容协议。

## 启动条件

创建 MySQL 8.4 数据库 `dataguard` 并授权平台元数据专用账号。为安全起见，配置以下环境变量：

- `DG_MYSQL_URL`、`DG_MYSQL_USER`、`DG_MYSQL_PASSWORD`：MySQL 元数据库，正式环境应使用 `sslMode=VERIFY_IDENTITY`。
- `DG_ENCRYPTION_KEY`：随机生成的 32 字节密钥的 Base64 字符串，用于加密业务数据源密码。
- `DG_INITIAL_ADMIN_PASSWORD`：仅在空用户表首次启动时创建 `admin`，需 12-72 位复杂密码，第一次登录后必须修改。
- `DG_ALLOWED_SOURCE_HOSTS`：业务目标源主机白名单，以逗号分隔；不设置时一律不允许执行 SQL。
- `DG_SECURE_COOKIE=true`：正式 HTTPS 下启用安全 Cookie。
- 可选 `DG_ALLOWED_MCP_ORIGINS`：允许发起浏览器 MCP 跨域请求的精确 Origin 列表。

本机开发可先启动后端 `mvn spring-boot:run`，前端 `cd frontend && npm install && npm run dev`。正式构建先在 `frontend` 运行 `npm run build`，静态文件将直接写到 `src/main/resources/static`，再运行 `mvn clean package`。

## 使用顺序

1. 管理员登录并完成首登改密；在系统设置中新建审批用户。
2. 新增数据源，配置物理只读账号，开放指定表及字段。
3. 创建 Agent，保存只显示一次的 MCP Token，单独授权 Agent 表与字段。
4. 创建有效规则，指定请求行数区间、动作和需要审批时的人员；规则为空时默认拒绝。
5. Agent 以 `Authorization: Bearer dg_...` 调用 `POST /mcp`。支持 `list_datasources`、`get_schema`、`describe_table`、`execute_readonly`、`get_access_request`。
6. 需要审批时，MCP 结果含 `status=PENDING` 和 `requestId`，审批人处理后 Agent 轮询并重试**同一请求**，通过的授权只能消费一次。
7. 在工作台、审批中心、访问记录核查行为。

详情见 [产品架构](docs/ARCHITECTURE.md)。

> 当前处于独立项目初始实现阶段，未完成真实生产验收。请勿未经测试直接连接生产库。