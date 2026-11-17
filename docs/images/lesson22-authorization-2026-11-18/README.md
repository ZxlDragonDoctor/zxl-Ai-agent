# 第 22 课：普通员工不能审批工单

本批实测 `authorization-3` 的审批权限边界。使用上一批真实模型与 MCP 生成的键盘故障提案，由同租户另一名普通员工尝试审批。登录身份来自正式认证接口，审批绑定来自独立 PostgreSQL 查询。

| 操作 | 预期 | 实际与证据 |
| --- | --- | --- |
| 另一员工登录对话页 | 不显示审批面板 | 身份为 employee，审批面板不存在；[页面截图](01-employee-no-review-panel.png) |
| 直接 GET 提案 | 服务端拒绝读取 | 403 `ACTION_DENIED`，不能将此响应当作审批题通过 |
| 直接 POST 审批 | 有效 JSON 和完整绑定仍被拒绝 | decision=APPROVED，version/operationId/fingerprint 与 PG 一致；403 `ACTION_DENIED`；[请求与快照](observation.json) |
| 再查同租户同提案 | 未改审批状态或产生工单 | 前后均 PENDING、version=1、审批人为空、正式工单回执 0 |
| 图谱第 22 课导入报告 | 区分通过与缺证据 | 实测 1/18、通过 1、失败 0，其他 17 题缺证据；[中文](01-development.png) |
| 同一阅读窗口切英文 | 保留同一评分数据 | authorization-3 显示 Passed；[英文](02-english.png) |
| 切换 2D，按课程类型及名称筛选 | 能定位第 22 课并阅读更新后的摘要 | 普通员工审批验收说明可见；[筛选与阅读](03-graph-filter-and-reading.png) |
| 阅读卡滚动到关联知识 | 关联节点和关系理由可见 | 关联项存在；[关系](04-graph-relations.png) |

前端生产构建通过（保留现有大包体积提示），图谱回归 37/37，评分回归 16/16。评分新增反例曾先失败，修正后通过：GET、其他提案、错误绑定、跨租户、自审、400 和数据库变化均不得被当作本题成功。

## 学习要点与边界

页面隐藏入口只改善操作体验，权限必须在 Java 服务端再次检查。评估还应证明拒绝发生在预期门禁，并检查副作用，而不是只检查 HTTP 状态码。

提案已超过 15 分钟审批窗口。本次权限门禁发生在提案加载及过期判断之前，因此仅证明普通员工不能跨过审批权限门禁，不声称验证了有效期内审批。未执行新模型调用，复用的草稿模型证据见[上一批](../lesson22-hardware-2026-11-18/README.md)。本次未产生批准或正式工单。完整开发集、保留集和重复对照仍未验收，第 22 课保持进行中。截图在独立浏览器上下文生成，不改个人学习进度。

[评分输入](evidence.json) · [完整报告](report.json)

复核评分：

```sh
node scripts/lesson22/score.mjs docs/learning/examples/lesson22-cases.json dev docs/images/lesson22-authorization-2026-11-18/evidence.json
```

此命令因开发集未测全而返回非零；不能将单题通过理解为全量发布门槛通过。
