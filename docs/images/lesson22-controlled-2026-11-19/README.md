# 第 22 课：可控机制验收

本批 24 项 Java 测试通过，无失败、错误或跳过；评分器 23 项回归通过。首轮因 PostgreSQL 未启动产生 7 项连接错误，恢复专用实例至 15493 后重新运行通过。不是新增真实模型质量验收。

| 案例 | 实际触发与验证 |
| --- | --- |
| tool-failure-3 | 正式 SDK SSE 发现成功后关闭测试自有服务，ResearchRunner 发布 TOOL_STARTED 和网络错误 |
| tool-failure-4 | 实际 lookup 返回超限响应，生产适配器拒绝，公开事件保留 MCP_INVALID_RESPONSE |
| tool-failure-5 | lookup 确实进入后阻塞，1200ms 客户端子预算触发生产截止，未进入回答与验证阶段 |
| budget-3 | 占用 Worker 后确认排队，推进受控时钟超过 1000ms，过期任务不再晚启动 |
| budget-4 | 规划模型函数确实进入等待，截止后保留清理所有权，函数返回后完成清理 |
| budget-5 | 两任务进入真实汇总路径，截止后晚到答案不能改写终态 |

预算测试使用生产 Orchestrator/ExecutionRuntime、确定性模型和受控时钟；报告 elapsedMs 是物理执行耗时，不能据此声称 1000ms 生产延迟指标。预算写入数由真实 PG 全表回执前后计数差观察（countsScope=database-global）；工具测试使用真实 PG 身份授权、正式 MCP SSE、生产 ResearchRunner，写入按其合成租户独立查询。没有伪造 Orchestrator 报告。工具测试没有生产运行 ID，评分记录不填写 runId。

`tool-failure-2` 另有 typed null 适配器合同测试，已通过，但不证明畸形 wire JSON 的完整链路，因此未纳入这份固定案例评分。`budget-2` 的实际工具等待阶段仍缺证据。保留这些缺口，不将可控集成与历史 qwen-plus 质量报告合并比较。

生产修正：ResearchToolResult 保留允许的适配器错误分类并剥离原消息与数据；同时允许公开 SKIPPED_EMPTY_INDEX 阶段。两个新增用例先失败后通过。测试入口为 ResearchToolResultTest、ResearchRunnerTest、ResearchFailureClassificationTest、Lesson22ToolFaultPgTest、Lesson22BudgetPhasesTest。

[实际观察](observations.json) · [开发集评分](dev-report.json) · [保留集评分](holdout-report.json) · [开发集输入](dev-evidence.json) · [保留集输入](holdout-evidence.json)
