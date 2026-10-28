# 第十七课：执行时重新授权

## 概念

登录只证明身份；运行期间的权限可能撤销。模型请求、工具请求、结果保存与结果发布都要重新检查。一个操作若同时需要 Agent 执行权和会话归属，必须在同一个 PostgreSQL 事务中检查，不能在两次独立检查之间留下权限变化窗口。

`AuthorizationService.gateAll` 接受 Java 代码选择的权限列表，记录每项决策。全部允许才执行短启动回调。拒绝或回调抛出运行时异常时，已产生的授权审计仍提交，再向上抛错。它不是跨数据库与远程服务的分布式事务：远程请求已经开始后，数据库提交失败不能撤回请求。

`OperationBoundary` 是执行接口；`ActorBoundary` 显式捕获可信身份与权限。正式代码不能通过线程局部变量或模型参数推断身份。无约束实现仅用于旧课堂入口和离线对照。

## 验证

真实 PG 的 `GateAllPgTest` 共 5 项通过：同一事务内的组合权限、拒绝时不执行操作且保留审计、启动失败保留审计，以及异步等待不占授权锁、撤销后禁止发布。先执行失败用例，再修复审计回滚问题。

阶段日志位于本地 `target/lesson17-gate-all-pg-red.log`、`target/lesson17-gate-all-pg-green.log`；随后 Maven 回归见 `target/lesson17-boundary-regression.log`。这些是服务边界测试，不代表正式 Agent、浏览器或真实模型整条链路已经验收。

## 模型流式边界

`DashScopeAgentStreamer` 的正式六参入口要求显式边界：在实际订阅模型传输时授权，等待响应在事务外；每次内容增量和最终结果发布前重新校验。旧五参接口保留课堂兼容性，正式 Agent 不应调用它。

取消等待不依赖模型返回下一帧。每 50 毫秒检查取消信号，取消后释放订阅。新增的本机 HTTP 用例先复现无帧时等待不退出，再修复；5 项模型传输测试通过。它们没有调用收费模型，不能冒充真实模型验收。

## SSE 准备与发布

`BaseAgent.prepareStream` 只准备任务，不启动工作线程。正式 MVC Controller 使用 `PreparedAgentStreamInterceptor.attach` 绑定，等 Spring 初始化输出 handler 后，`afterConcurrentHandlingStarted` 才激活任务。这样避免在输出 handler 尚未就绪时，把敏感内容提前缓存在 `SseEmitter` 中。请求在激活前失败会放弃准备、释放会话 lease；旧课堂 `runStream` 仍立即启动，绑定授权边界后的 Agent 禁止调用该旧入口。

事件使用固定线程数和有界队列，在准入与消费时检查当前权限。慢消费者不会持有 PG 锁；终态发布失败也必须清理并释放运行资源。撤销不能撤回已经开始发送的网络字节，不能声称数据库与 TCP 原子提交。

2026-10-28 复核：`PreparedAgentStreamTest` 6 项、`AgentPublicationBoundaryTest` 7 项、`ScopedConversationServiceTest` 10 项通过，包括实际 MockMvc 生命周期、控制器异常、准备后放弃、排队期间撤销和队列饱和。此处没有替代正式主应用重启后的浏览器验收。

角色示例 `LoveAssistantProvider` 已提供显式 ActorRef 创建方法，拒绝缺失身份和不支持的 Skill 参数，不增加工具。执行服务仍负责在准备运行前绑定会话/运行权限；Provider 接受身份不等于独立完成授权。`ScopedLoveProviderTest` 2 项通过；统一 Agent 类型目录与其余 Provider 的正式接线继续完成。
