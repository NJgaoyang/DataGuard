# DataGuard 前端 UI Design System V1.0

本次以 Vue 3 + TypeScript + Ant Design Vue 4 为基础，不迁移 React 或 Element Plus。新增 ECharts 5 和 Monaco Editor。主分支合并前先在独立开发分支验收。

## 公共组件

| 组件 | 场景 | 业务约束 |
|---|---|---|
| DgDataTable | 数据源、Agent、规则、审批、访问记录及最近访问 | 透传表格事件、列与分页，不修改后台协议 |
| DgFilterBar | 查询、重置、刷新操作 | 查询提交筛选条件；重置只改输入、不触发查询 |
| useListSearch | 客户端列表 | 输入草稿与已应用筛选分离 |
| DgDetailPanel | 详情及返回操作 | 保留原面包屑和权限判断 |
| DgPermissionFields | 数据源和 Agent 字段授权 | 原有字段掩码、禁用规则和保存接口不变 |
| DgSqlViewer | 安全 SQL 审计模板 | 无 HTML 插值，不展示真实参数，支持高亮和复制 |
| DgSqlEditor | 可复用 Monaco SQL 编辑器 | 审计详情按需动态加载且只读；不发送 SQL 执行请求 |
| DgDashboardCharts | 工作台 ECharts 图表 | 根据 /api/dashboard 的 recent 最近记录聚合，不虚构全量历史 |

## 主题与布局

统一设计 Token 存放在 frontend/src/design/tokens.css，Ant Design Vue ConfigProvider 主题位于 frontend/src/design/theme.ts。
页面统一表格、详情、按钮、搜索操作和默认空状态。设计颜色包括 Primary #1766e8、Success #18a76e、Error #e04757、Warning #d59529。
修改设计色彩时应同步更新两个文件。

## 实施范围与限制

- 数据源、Agent 管理、访问规则和审批中心统一采用点击“查询”才提交搜索；点击“重置”恢复搜索输入但不立即刷新列表。
- 审批分类标签切换仍立即向后端请求新的 scope，这是业务导航而非搜索草稿。
- 访问记录仍默认当天；修改筛选不自动查询；重置不自动查询；服务端分页每页 10 条。
- ECharts 仅表示后端返回的最近访问记录；完整历史趋势须新增真实聚合 API 后才显示。
- Monaco 在访问详情仅为 SQL 审计模板查看器，不允许执行查询；未来 SQL Gateway 才有独立 SQL 编辑/执行场景。
- P2 移动端暂保留当前响应式页面，暂不另开发 H5 或小程序。

## 验证

在 frontend 执行 npm ci、npm run build、npx playwright test。测试覆盖共享筛选的草稿/重置行为、图表使用真实记录、只读 SQL 编辑器与原有权限/审批/审计回归。