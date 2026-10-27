# 安装与快速开始

本文以 `main` 主分支为基线，从**开发/测试环境**构建一个可用的 DataGuard。生产环境须另外完成 TLS、备份恢复、最小权限、告警和压力演练。

## 1. 环境准备

| 依赖 | 用途 |
|---|---|
| JDK **21**、Maven 3.9+ | 构建 Spring Boot JAR |
| Node.js **22**、npm | 构建 Vue 3 管理界面 |
| MySQL **8.x**（CI 8.4） | 平台元数据、账号、审计、规则与审批 |
| MySQL 或 StarRocks | 被受控查询的业务源；独立只读账号 |
| 可选：Caddy、systemd | HTTPS 反向代理、进程守护 |

仓库使用 Maven 与 Vite 构建，静态前端资源由 Java 应用提供。业务源和平台元数据库**不是同一个身份/权限体系**。禁止将平台写权限账号配置为 Agent 的业务查询账号。

## 2. 克隆与构建

```bash
git clone https://github.com/NJgaoyang/DataGuard.git
cd DataGuard
git switch main

cd frontend
npm ci
npm run build
cd ..

mvn -B -ntp clean package
```

生成 `target/dataguard-1.0.0-SNAPSHOT.jar`。若只需本地验证编译可使用 `mvn -B -ntp -DskipTests package`；正式提交前应运行 `mvn -B -ntp verify` 并执行浏览器测试。

## 3. 配置元数据库与环境变量

先创建独立元数据库及其账号（数据库/账号名自行定义）。数据库用户须有 Flyway 创建与升级表所需权限，不要让业务源用户复用该账号。

设置以下配置（示意，**不要复制假值投入生产**）：

```bash
export DG_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/dataguard?sslMode=VERIFY_IDENTITY'
export DG_MYSQL_USER='dataguard'
export DG_MYSQL_PASSWORD='从安全凭据系统取得'
export DG_ENCRYPTION_KEY='Base64编码的32字节随机密钥'
export DG_INITIAL_ADMIN_PASSWORD='首次管理员强密码'
export DG_ALLOWED_SOURCE_HOSTS='业务MySQL域名或IP,StarRocks域名或IP'
export DG_SECURE_COOKIE=true
```

| 变量 | 说明 |
|---|---|
| `DG_MYSQL_URL`、`DG_MYSQL_USER`、`DG_MYSQL_PASSWORD` | DataGuard 元数据库 JDBC 连接 |
| `DG_ENCRYPTION_KEY` | AES-256-GCM 用于加密业务源凭据；丢失后已有密文可能无法恢复 |
| `DG_INITIAL_ADMIN_PASSWORD` | 首次初始化管理员密码，登录后必须改密 |
| `DG_ALLOWED_SOURCE_HOSTS` | 明确允许读取的业务数据库主机；不允许任意目标 |
| `DG_BIND`、`DG_PORT` | 默认 `127.0.0.1:8080` |
| `DG_SECURE_COOKIE` | HTTPS 环境设为 `true`（当前默认 true） |
| `DG_ALLOWED_MCP_ORIGINS` | 允许发送浏览器 `Origin` 的 MCP 来源列表；无 Origin 的桌面/后端连接另行处理 |
| `DG_ALLOW_INSECURE_LOOPBACK_STARROCKS` | 仅开发隔离环境需要时开启，不应用于公网业务源 |
| `DG_SOURCE_TRUSTSTORE_URL`、`DG_SOURCE_TRUSTSTORE_PASSWORD` | 业务源 TLS 私有 CA / truststore 相关设置 |

数据库地址、示例 localhost、TLS 证书配置应对应真实部署环境；如果 MySQL 启用验证服务端身份，主机名与证书必须匹配。不要设置 `DG_SECURE_COOKIE=false` 来绕开公网 HTTPS 问题。

## 4. 运行与登录

```bash
java -jar target/dataguard-1.0.0-SNAPSHOT.jar
curl --fail http://127.0.0.1:8080/actuator/health
```

应用自动执行 Flyway **V1–V8**，包含规则修订、限流设置、函数白名单、Agent 软删除与 SQL 审计字段。新库自动建表；升级已有库前必须备份。

通过**受信任的 HTTPS 地址**访问管理控制台；初始管理员用户名为 `admin`，按设置的首次密码登录并修改密码。控制台使用登录 Cookie + CSRF，**不能直接拿 Cookie 替代 MCP/REST Agent Token**。

## 5. 建立最小查询授权

1. **数据源**：新增 MySQL/StarRocks，填入物理只读账号；检测连接并明确允许需要的表、字段。
2. **Agent 管理**：创建 Agent、确认归属用户；一次性复制 Token 并保存在客户端私有凭据区。运行中的 Agent 不允许改授权，先停用再分配表/字段，最后启用。
3. **访问规则**：配置允许、拒绝或需要独立审批人的规则；无匹配默认拒绝。授予源/字段权限本身不会自动放行查询。
4. **MCP 或 REST**：通过 Bearer Token 先枚举被授权目录，再提交校验/查询。
5. **审批中心**：若返回 `PENDING`，由合规审批人处理；通过后用相同 SQL 与参数再查询。
6. **访问记录**：查看执行状态、脱敏 SQL 模板与审批轨迹。旧的未保存 SQL 的记录不能补造。

## 6. 发出第一条只读 SQL

```bash
export DATAGUARD_URL='https://110.42.132.44'  # 替换成自己的 HTTPS 入口
export DATAGUARD_TOKEN='从 DataGuard Agent 管理获取'

curl -fsS "$DATAGUARD_URL/api/v1/agent/datasources" \
  -H "Authorization: Bearer $DATAGUARD_TOKEN"

curl -fsS "$DATAGUARD_URL/api/v1/agent/queries/validate" \
  -H "Authorization: Bearer $DATAGUARD_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"sourceId":"替换为数据源 UUID","sql":"SELECT COUNT(*) AS total FROM orders LIMIT 1","params":[]}'
```

`COUNT(*)` 与其他表均需要有效权限和匹配规则。实际查询用 `POST /api/v1/agent/queries`。若返回 `403`，先检查表/列授权与规则；`401` 检查 Token/停用状态；`429` 检查请求与分钟配额。

## 7. 下一步

- [控制台与业务工作流](CONSOLE_GUIDE.md)
- [MCP 和客户端配置](MCP_INTEROPERABILITY.md)
- [REST API](REST_AGENT_API.md)
- [SQL 安全边界](SQL_QUERY.md)
- [运维发布与回滚](OPERATIONS.md)

**不要把开发测试站 IP、演示密码或 CI 通过视为生产安全认证。**