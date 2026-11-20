# 第 23 课：模型发送边界上的记账

账本类只证明数据库机制；只有接到真正的网络发送处，才能阻止额度不足时继续调用。本批把记账放入 DashScopeAgentStreamer，保留显式权限边界，正式多 Agent 和 ScopedResearchFactory 的选择/回答调用使用可信 actor/run 账户。

## 一次调用的状态

调用前 reserve；取消发生在领取发送许可之前时，可以释放未发送预留。领取许可后才进入最终授权订阅 gate。完整且一致的 prompt/completion/total usage 按真实数量结算；缺失或不一致保留 UNKNOWN，不写成零。已收到 usage 后再发生权限拒绝，不能发布模型内容，但仍须记入已观察用量。账本异常阻止成功返回，不用备用模型绕开记账失败。

DISPATCHED 表示已领取发送许可，不保证供应商已经收到请求。最终 gate 拒绝时没有 HTTP 请求，但许可已领取，因此本实现保守保留 UNKNOWN；显式运维对账仍待实现。

## 不在权限事务中嵌套取连接

审查发现旧接法在 ActorBoundary.start 的事务内调用 ledger.dispatch，又向同一个连接池取连接。单连接池下两个真实 Actor 并发，测试复现 AUTHORITY_UNAVAILABLE。修复后 dispatch 使用独立事务并在权限 callback 外完成，最终 gate 只包订阅动作，不持有 SQL 事务等待模型响应。新池测试、流预算与路由集成定向回归均通过。

## 一次降级也是一次新调用

BoundedModelRouter 只接受部署配置的主模型和一个不同的备用模型。仅真正的 HTTP 429/503、未输出、未取消、无权限或记账异常时允许一次降级。Reactor 的诊断 traceback 不当作记账错误；其他 suppressed 异常保守禁止降级。没有从任意错误文本猜测状态码。

每次尝试都从 Supplier 领取新的 TokenCallBudget。真实 HTTP 夹具验证了第一次请求体选择主模型并返回 503，第二次请求体选择备用模型，数据库记录两次受理；主调用因无 usage 保留预留，备用调用已知用量独立结算。这不是对真实供应商故障率或模型质量的评估。

## 证据分层

- 最终 90 项 Java 专项及相关回归通过，无失败、错误或跳过。
- 真实 qwen-plus 直连返回 21 token，PG 中 SETTLED、actual=21。
- 真实认证 HTTP 多 Agent 请求完成规划、Research、MCP 空检索和正确拒答；两笔账本记录分别结算 1027、842，共 1869，预留归零、清理完成。
- 实际工具事件显示 vector=SKIPPED_EMPTY_INDEX、无降级，验证了先前空知识库修正的新 Worker 链路。
- 阅读卡显示直连与 HTTP 两类历史验收快照；它不是当前会话的实时预算控制面板。

[原始观察与页面截图](../images/lesson23-usage-2026-11-20/README.md) · [课程配置和未完成范围](23-budget-routing-and-fallback.md)

默认开关仍为 false；本机验收实例显式开启。尚未覆盖所有 Love/Manus/独立回答、embedding 和 reranker 入口；每 run 配额也不能替代租户周期总额、供应商账单、模型能力路由、持久审计或 UNKNOWN 对账。本课继续进行中。
