# 第 22 课：八项可控机制证据

本批总回归 30 项 Java 测试通过，评分及投影回归 26 项通过。V8 账本迁移与真实 PostgreSQL 测试也包含在总回归中；没有新增真实 LLM 调用。

在上一批六项机制之外，补齐两项：

- `tool-failure-2`：真实 PG 授权成功后，测试服务经实际 SDK SSE 返回 `TextContent("{broken")`，生产适配器拒绝并发布 `MCP_INVALID_RESPONSE`。不是把 SDK 返回值替换为 null。
- `budget-2`：ResearchRunner 的 1000ms 总预算在实际 lookup 进入阻塞后耗尽。真实单调时钟计时，确认 lookup 阻塞段退出、没有 TOOL_COMPLETED 或晚到回答。它不是 Orchestrator 运行，也没有伪造 runId。cleanupConfirmed 只证明这条测试的 lookup 阻塞段已退出，不代表生产所有服务资源都已释放；测试最终还关闭自有 SDK 客户端和服务。

截止路径不允许继续发布过期事件，因此只有真实 REQUESTED/STARTED 事件，没有补造 TOOL_FAILED。投影器结合实际 lookupEntered、RESEARCH_DEADLINE、阶段确认及退出证明判断此案例。其余预算测试仍使用受控时钟，不用于线上性能结论。八项观察的模型统一明确标为 controlled-java-fixture；数据库计数范围也各自保留。

`scripts/lesson22/project-controlled.mjs` 是可复跑的严格投影入口，拒绝未进入阶段、未清理、错误分类不匹配、缺真实工具事件、typed null 替换或无效写入计数。新增用例先失败后实现。完成上述 Java 测试生成 target 观察后，可运行：

```sh
node scripts/lesson22/project-controlled.mjs docs/learning/examples/lesson22-cases.json dev controlled-stage-fixture-v2 target/lesson22-tool-failure-2-wire-observation.json target/lesson22-budget-2-controlled.json
```

评分 CLI 对整套分组仍返回非零，因为其他题不属于这份机制报告，不能把八项机制通过当作整课通过。开发集显示 4/18、保留集 4/12。[实际观察](observations.json) · [开发集输入](dev-evidence.json) · [开发集报告](dev-report.json) · [保留集输入](holdout-evidence.json) · [保留集报告](holdout-report.json)

课程阅读卡的可控报告入口已更新到本批快照。历史六项报告保持原样，可单独回溯。真实模型全量重复对照、失败设备草稿及新 Worker 空库修正的真实模型验收仍待完成。
