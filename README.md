# DataGuard

AI 数据库安全访问网关。从零开始独立开发，不依赖 MCP-DataGuard 仓库或其历史代码。

开发分支：`feature/ai-access-gateway-v1`。设计目标：AI Agent 通过 MCP 读取 MySQL / StarRocks；平台负责表字段授权、读数限制、规则匹配、人工审批及完整审计。默认拒绝未明确授权的请求。
