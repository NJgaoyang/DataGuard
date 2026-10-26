# DataGuard 开发机安全与发布验收

> 2026-10-26；适用于 **NJgaoyang/DataGuard** 新项目，不适用于旧 MCP-DataGuard。以下将已实测事项与生产待验收事项明确区分。

## 1. 已完成并确认的开发机事项

| 项目 | 核查结果 | 验收范围 |
| --- | --- | --- |
| 公网 HTTPS /actuator/health | UP | 开发机外网入口 |
| MySQL 元数据库 | Flyway V1–V3 正常；dg_requests、dg_audit 持续存储 | 开发机 MySQL 持久化卷 |
| MySQL / StarRocks 读取和独立人工审批 | 隔离演示库、真实 JDBC、请求审批、重试和审计已通过 | 见 P0_LIVE_ACCEPTANCE.md |
| StarRocks 数据持久化 | FE 元数据、BE 存储以及配置分别挂载到 /var/lib/dataguard-starrocks；重启后原演示表仍有 3 行 | 容器重新启动和数据复查 |
| Java 故障自恢复 | 通过 SIGKILL 终止 Java 主进程，systemd 自动恢复，公开健康检查恢复 UP，dg_requests 前后均为 4 条 | 开发机系统级故障演练，不等于完整压测 |
| 安全基础能力 | 登录失败锁定；每 Agent 每分钟最多 60 个 MCP 调用和 3000 行请求额度；Token 轮换/撤销 | 代码和 CI 集成测试 |
| 页面 | 卡片跳转携带筛选、统一标题和操作区、中文日期、每页 10 条，独立详情 | Chromium 自动化回归 |
| 运行监控 | 每分钟本机 systemd 监测应用、反向代理、两个数据库容器与磁盘；按日汇总可用性 | systemd 定时任务已启用 |
| 受控发布 | 在干净 Git 工作树执行 npm ci 和 Maven 打包，保留前一版本 JAR，健康失败时回滚 | 发布和健康检查已经执行 |

## 2. 发布和回滚操作

本项目**单体 Java 服务无需 Docker 部署**。请使用如下流程，不要从含未提交改动的开发目录直接制作发布包。

1. 签出 GitHub 功能分支的确切 Git SHA 到干净工作树。
2. 执行 `cd frontend && npm ci && npm run build`，再运行 `mvn -B -ntp verify` 或经 GitHub CI 认证后执行 `mvn -B -ntp -DskipTests package`。
3. 运行 `sudo bash deploy/dataguard-release.sh deploy target/dataguard-1.0.0-SNAPSHOT.jar <40位GitSHA>`。
4. 通过 `sudo bash deploy/dataguard-release.sh status` 核实当前 SHA、JAR SHA256、service 状态。
5. 发布失败会自动恢复前一份 JAR；人工回退使用 `sudo bash deploy/dataguard-release.sh rollback`。回退后必须验证实际功能和数据库迁移兼容性（Flyway 一般不自动降级）。
6. 发布记录位于 `/var/lib/dataguard-new/releases/deployments.tsv`，当前源码版本在 `current-git-sha`。**不要直接把不受控的 JAR 覆盖 `app.jar`**。

> rollback 只恢复应用二进制，不回滚 MySQL DDL/数据。任何破坏性数据库迁移需要另外设计前向兼容与备份恢复方案。

## 3. 运维检查

- `systemctl status dataguard-new dataguard-caddy`
- `systemctl list-timers --all | grep dataguard`
- `journalctl -t dataguard-monitor -n 50 --no-pager`
- 每分钟检查结果：`/var/lib/dataguard-new/monitor/availability.tsv`
- 日可用性汇总：`/var/lib/dataguard-new/monitor/daily.tsv`
- 数据库持久化：MySQL 使用 Docker 数据卷；StarRocks 的宿主机目录为 `/var/lib/dataguard-starrocks`。原 StarRocks 容器已保留为回滚参考，不得在确认新存储稳定前删除。
- 私有演示凭据仅存在开发机的 `/etc/dataguard-new/p0-demo/credentials.json`，不应提交或打印。

## 4. 实际上线前仍必须完成的工作

- **外部兼容性**：从独立机器，以真实目标版本认证 Claude/Cursor/Cline 等 MCP 客户端；DataGuard 当前只提供 JSON 响应模式，不承诺 SSE。
- **登录安全**：当前账户名级锁定可被恶意反复尝试利用来使合法用户暂时无法登录；正式对公网开放时还应在反向代理/WAF 施加 IP 和路径级速率控制，明确解锁/告警机制。
- **审批和撤权**：多 Agent 并发配额由 MySQL 集成测试覆盖；生产环境仍需在真实权限变更和查询执行竞争条件下进行专门演练。
- **告警送达**：systemd/journald 已可发现服务异常，但尚未配置真正的外部告警接收系统；不能声称已有可靠的远程通知。
- **备份与恢复**：持久化卷不等于异机备份。MySQL 和 StarRocks 仍需离机、周期性、已验证可恢复的备份方案。
- **审计**：审计持久化于 MySQL，尚不是独立不可篡改/WORM 审计。
- **性能**：目前没有持续负载、长时间压测以及真实第三方 Agent 混合负载的验收证据。

## 5. P0 工作归档

- Agent 调用额度、登录锁定、Agent Token 轮换、数据源连接测试/凭据更新、审批站内通知与运维概览均已落到 API 和管理界面。
- 访问记录和审批事件继续存放于 MySQL，查询记录后端分页每页 10 条，未改为前端缓存。
- 每次添加并发安全测试后应以 **CI 的最终结论** 为准；单个失败的 CI 不可算通过。
