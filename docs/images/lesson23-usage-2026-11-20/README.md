# 第 23 课：模型发送、预算和有限降级验收

本批最终 90 项 Java 专项及相关回归全部通过，无失败、错误或跳过；前端生产构建与 37 项图谱回归通过。不是全仓库测试或生产压测。

| 证据层 | 实际结果 |
| --- | --- |
| 真实 qwen-plus 直连流 + PG 账本 | 返回 OK；供应商 usage=21，PG actual=21，预留归零，SETTLED |
| 真实认证 HTTP 多 Agent + MCP + PG | 独立员工登录后完成规划、Research、空检索与正确拒答；两笔 SETTLED 分别 1027、842，共 1869 token，预留归零、清理完成 |
| 独立业务写入检查 | 合成租户正式工单回执 0，不把预算账本写入当作业务工单 |
| 新 Worker 空库修正 | 实际 TOOL_STARTED/COMPLETED，hits=0，vector=SKIPPED_EMPTY_INDEX，degradations=[] |
| 真实 HTTP 夹具的降级 | 首次请求体选择主模型并返回 503，备用请求体选择另一模型；每次独立占额。主调用无 usage，保留 UNKNOWN；备用已知用量独立结算 |
| 连接池回归 | 旧实现单连接池复现 AUTHORITY_UNAVAILABLE；修复后两个真实 ActorBoundary 并发发送成功。最终授权拒绝不发送请求，已领取许可保守保留 UNKNOWN |

直连采用独立合成账户，不经过 HTTP 身份工作流；另一份 HTTP 观察则经过正式身份、运行归属、工具和报告接口。降级故障是可控夹具，不是真实供应商故障。主模型默认 qwen-plus，备用模型未配置；本机验收实例显式开启 agent.usage.enabled。

[直连观察](live-usage.json) · [认证 HTTP 观察与工具阶段](http-usage.json) · [来源代码与机制导读](../../learning/23-usage-integration.md)

## 页面逐状态验收

验收前端位于本机 5212，代理本机 18301；认证后端、知识 Worker 与 MCP 已恢复运行。后端基于既有验收副本定向更新本批 class/迁移，未全量复制当前工作区构建结果；这不是全新 checkout 的可复现部署验收。其他未提交内容没有混入 Git 提交。

| 操作 | 预期与实际 |
| --- | --- |
| 第 23 课打开所选知识 | 显示历史验收、21 token、4096 预留与 SETTLED；[中文](01-live-usage-zh.png) |
| 切英文 | 相同数字与历史快照边界保留；[英文](02-live-usage-en.png) |
| 切 2D，再回 3D | 阅读卡和证据不丢失；[2D](03-switch-2d.png) · [3D](04-return-3d.png) |
| 滚动到 HTTP 验收 | 显示 completed、2 次受理、1869 token、零预留、清理完成；[HTTP 记录](07-http-usage-en.png) |
| 打开 Token 预留知识节点 | 显示预留、UNKNOWN、连接池和配额范围解释；[概念](05-token-concept.png) |
| 点击关联源码并切英文 | 定位 TokenBudgetLedger.java；[源码关系](06-linked-source-en.png) |
| 第 22 课选择新增真实空库报告 | 实测 1/18，通过 1，其他题缺证据；[中文](08-live-empty-report.png) · [英文](09-live-empty-report-en.png) |

阅读卡是验收快照，不是当前会话实时预算，也不修改个人学习状态。Token 不是金额，usage 不是供应商账单。

## 复跑入口

`scripts/lesson23/Lesson23LiveUsage.java` 从环境变量 LESSON23_MODEL_KEY 读取模型凭据，使用本机数据库环境变量；通过应用类路径运行，输出 target/lesson23-live-usage.json。未完成已知用量结算时保存失败观察并以非零退出。不要将凭据写进命令参数、文档或 Git。

`scripts/lesson23/run-http-budget.mjs` 只接受显式 loopback origin 和 lesson23-* 合成租户。前置条件为正式服务就绪、预算开关开启、主模型配置 qwen-plus、新建空知识库测试租户及其 employee/maintainer 身份（可复用 Lesson22GroundingSeed）。设置 LESSON23_ORIGIN、LESSON23_TENANT、LESSON23_EMPLOYEE_PASSWORD、LESSON23_MAINTAINER_PASSWORD、LESSON23_PSQL 和 PG 客户端连接变量后运行：

```sh
node scripts/lesson23/run-http-budget.mjs
```

脚本通过真实 HTTP 运行，再独立查询 PG 账本与工单回执。失败观察保存在 target；未确认完整 Research、空库跳过、已知结算与零业务写入时非零退出，不自动重试模型。模型标签是本次部署约束，不是供应商签名或固定权重版本。

## 第 22 课补充证据

本次固定问题对应 abstention-3，严格投影确认同任务真实成功空检索、明确拒答、无降级、无业务写入，记录已知总用量 1869。[评分输入](abstention-evidence.json) · [评分报告](abstention-report.json)。知识/配置/提示词模式与早前失败报告不同，不自动合成质量改进对照，也不追认旧失败已通过。

## 尚未覆盖

每 run 配额不限制同租户不断新建 run 的周期总额。其他 Love/Manus/独立回答、embedding/reranker 入口、模型能力注册表路由、持久路由审计、UNKNOWN 运维对账和实时预算事件页面仍待补齐。完整 HTTP 已验证上述一例，不代表所有取消、降级和业务流程都已通过；第 23 课保持进行中。
