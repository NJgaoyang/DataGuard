# 开发机公网 HTTPS 访问

当前开发机：`https://110.42.132.44/`，HTTPS 入口由宿主机 Caddy 2.11.7 提供，反向代理到仅绑定 `127.0.0.1:8080` 的独立 DataGuard Spring Boot 单体应用。

- Caddyfile 位于 `/etc/caddy/Caddyfile`，systemd unit 位于 `/etc/systemd/system/dataguard-caddy.service`，对应的模板包含在本目录。
- 前置条件：腾讯云安全组允许 TCP/443（和可选用于 308 重定向的 TCP/80）；不要暴露 8080、3307 或 9030。
- 有效证书由 Let's Encrypt 签发给公网 IPv4 地址，使用 `shortlived` profile（约六天），Caddy 自动续签，证书状态可用 `echo | openssl s_client -connect 127.0.0.1:443 2>/dev/null | openssl x509 -noout -dates -issuer -ext subjectAltName` 查看。
- 强制 `DG_SECURE_COOKIE=true`，管理登录 Cookie 为 Secure、HttpOnly、SameSite=Strict。
- HTTP 自动 308 跳转 HTTPS；未登录 `/api/sources`、`/mcp` 返回 401。
- 当前仅用于开发测试。管理员首次随机口令存储在开发机 `/root/dataguard-new-initial-admin.txt`，禁止上传 GitHub 或复制到页面。
- 如果公网 IP 更换，须同步修改 Caddyfile、重新签发证书及更新访问入口。
- 不要在未启用可信 TLS 证书的情况下使用明文 HTTP 暴露登录或 MCP Token。
- 如浏览器不能访问，而本机 `curl https://110.42.132.44/actuator/health` 返回 UP，请先检查腾讯云安全组是否允许浏览器来源的 TCP/443。
- `systemctl status dataguard-new dataguard-caddy` 检查两个宿主机服务；`journalctl -u dataguard-caddy -n 80` 检查证书续签。
