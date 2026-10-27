# 部署、运维、安全与备份

本文说明 **GitHub main** 当前可用的构建、发布和运维路径。示例 systemd/Caddy 路径参考开发机模板，生产环境须按其网络、证书和账号策略调整。

## 1. 运行拓扑

```text
HTTPS 客户端
  -> Caddy 或可信反向代理 :443
  -> Spring Boot DataGuard 127.0.0.1:8080
       -> MySQL 元数据（Flyway V1–V8 / dg_requests / dg_audit）
       -> 已允许的 MySQL / StarRocks 只读 JDBC 源
```

服务端是单体 Java JAR，由 systemd 守护。MySQL 和 StarRocks 可以由 Docker/其他编排运行；**Java 主服务本身不要求 Docker**。不应对公网开放内部 8080、MySQL 或 StarRocks 管理端口。

## 2. 构建与发布

依赖 Java 21、Node 22、Maven 和已配置的数据库/业务主机白名单。

```bash
git clone https://github.com/NJgaoyang/DataGuard.git
cd DataGuard
git switch main

cd frontend && npm ci && npm run build && cd ..
mvn -B -ntp clean verify
# CI 完整通过且从确切 Git SHA 构建 JAR 后，执行发布
git rev-parse HEAD
sudo bash deploy/dataguard-release.sh deploy target/dataguard-1.0.0-SNAPSHOT.jar "$(git rev-parse HEAD)"
sudo bash deploy/dataguard-release.sh status
```

仓库的发布脚本 `deploy/dataguard-release.sh` 使用独占锁、SHA 校验、systemd 重启及健康检查，保留 `previous.jar` / 前一版本 Git SHA。建议只从干净工作树的确切 SHA 发布，并在生产 CI/CD 中校验构建产物来源。

- unit 模板：`deploy/dataguard-new.service`
- 反向代理模板：`deploy/public/Caddyfile`、`deploy/public/dataguard-caddy.service`
- 监控：`deploy/dataguard-monitor.sh`、`deploy/systemd/dataguard-monitor.timer`
- 日报告：`deploy/dataguard-daily-report.sh`、`deploy/systemd/dataguard-daily-report.timer`

这些是模板与开发机约定，并不保证在另一台服务器上无需调整即可运行。

## 3. 环境与安全参数

重要变量说明见 [快速开始](GETTING_STARTED.md)。特别注意：

- 元数据库 `DG_MYSQL_URL/USER/PASSWORD` 须来自最小权限账号，Flyway 迁移账户应与业务只读角色分离。
- `DG_ENCRYPTION_KEY` 是业务连接密码的加密密钥，必须加密存储并纳入安全的备份/恢复计划。
- `DG_ALLOWED_SOURCE_HOSTS` 必须限制真实业务源；不要为了开发便利允许任意内网/公网主机。
- `DG_SECURE_COOKIE=true`，外部必须用可信 HTTPS，保护管理 Cookie 与 Agent Token。
- `DG_ALLOWED_MCP_ORIGINS` 只允许明确的浏览器来源；无 Origin 的桌面 SDK 客户端是另一类连接。
- `DG_ALLOW_INSECURE_LOOPBACK_STARROCKS` 仅供受控的本地隔离演示；生产建议完整验证源 TLS。
- Agent 调用与行数额度可通过后台系统设置维护，避免盲目扩大限制。
- 管理员角色、审批角色和业务源权限要拆分；Agent 软删除不能通过简单回滚 JAR 自动恢复。

## 4. 备份与恢复

**Flyway 前与每次发布前备份**至少以下内容：MySQL 平台元数据、审计/规则/审批、数据库账号与结构、应用二进制、加密密钥与 TLS 证书。数据库卷不是可恢复的异机备份。

开发机可使用 `mysqldump --single-transaction` 或托管 MySQL 的一致性快照；文件应加密且具有最小读权限。正式备份包含校验、保留策略和定期还原演练；业务 StarRocks 数据应按自身备份方案管理，不可只依赖其数据卷。

Flyway 升级通常是**前向**的：

- V7 新增 `deleted_at`、`token_reset_required` 并处理旧撤销 Token。
- V8 为访问记录新增 `sql_template`，旧 SQL 记录仍可能为空。
- **二进制回滚不会撤销 Flyway DDL，也不会回滚修改后的 Token/审批/授权数据。** 回滚前必须检查旧 JAR 是否兼容现存表结构；必要时使用经验证的数据库恢复流程。
- 严禁直接物理删除带审批/审计引用的 Agent、请求和用户数据，以免破坏历史链。

## 5. systemd 状态、日志与监控

```bash
systemctl status dataguard-new.service
systemctl status dataguard-caddy.service
curl --fail http://127.0.0.1:8080/actuator/health
journalctl -u dataguard-new -n 100 --no-pager
journalctl -u dataguard-caddy -n 100 --no-pager
systemctl list-timers --all | grep dataguard
sudo bash deploy/dataguard-release.sh status
```

开发机约定的发布记录：`/var/lib/dataguard-new/releases/deployments.tsv`，部署 Git SHA：`/var/lib/dataguard-new/releases/current-git-sha`。监控数据参考 `/var/lib/dataguard-new/monitor/`。不要把日志中的授权信息、业务字段、环境变量或数据库密码公开分享。

## 6. 回滚

```bash
sudo bash deploy/dataguard-release.sh rollback
sudo bash deploy/dataguard-release.sh status
curl --fail http://127.0.0.1:8080/actuator/health
```

脚本恢复保存的前一版 JAR，但数据库结构可能已经升级。出现错误时应先评估受影响数据和 Flyway 版本，再执行涉及 DB 的恢复。上线后需验证 MCP 工具、REST、审批、拒绝、访问记录和前端静态资源，不应仅以健康检查 200 作为全量验收。

## 7. 生产准备事项（尚不能标为已完成）

| 分类 | 必要工作 |
|---|---|
| HTTPS / 网络 | 稳定域名与自动证书续期、正确防火墙/安全组、可信业务源 TLS |
| 备份 | MySQL、StarRocks、密钥和配置的异机备份、真实恢复演练与 RPO/RTO |
| 身份 | 外部云 MCP 客户端若要求 OAuth，需要单独实现 OAuth 2.1 授权链路 |
| 高可用 | 单体 systemd 自动恢复不等于多副本 HA；需故障切换与状态一致性方案 |
| 安全 | WAF/代理级速率控制、密钥托管、审计防篡改/异地传输 |
| 资源 | 长时间负载测试、数据库侧大查询资源限制、JOIN 扫描成本治理 |
| 合规 | 权限变更竞争条件、审批人与申请人隔离、账号锁定告警 |
| 验收 | 目标客户端账号实连、DNS 与 TLS、每种目标数据库版本的业务链路 |

具体可重复验证方法见 [TESTING.md](TESTING.md)。不要再沿用早期“V1 不支持 JOIN、仅五个 MCP 工具”的旧验收文档。