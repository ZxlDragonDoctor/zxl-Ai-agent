# 第十七课：把可信身份接进真实 Agent

本课仍在进行。这里记录已经落地的执行单元；全部 Provider、Skill、多 Agent、跨标签页缓存与旧历史迁移仍需完成整体验收。

## 1. 先理解两条不同的数据流

业务参数是“查询什么、选择哪个策略”；身份上下文是“谁正在执行、允许读哪个范围”。模型只能提供前者。`ActorRef`、会话 ID 对应的归属和运行权限由 Java 的可信入口传递，不能从模型的 JSON 参数补出来。

统一对话沿用原页面：`FormalAgentTypeController` 返回已经支持可信身份的 Provider。注册表的 `supportsAuthenticatedExecution` 是能力声明，不是权限授权；真正执行仍需当前 Decision。前端从实际目录选择默认类型，避免保留目录里已不存在的 `manus` 值。

## 2. 知识助手的代码路径

1. `FormalAgentChatController` 从已认证请求取身份，严格读取消息和业务选择。
2. `AgentConversationService.prepareChat` 检查会话归属，创建运行 owner，保存用户消息，并绑定运行边界。
3. `KnowledgeAssistantProvider.create(actor, options)` 只接受支持的业务参数；缺少 scoped gateway 时拒绝，不回退 Alice。
4. Provider 把知识查询正文交给 `ScopedKnowledgeClient`。正文不含 profile/tenant/subject；Java 额外权限包含 Agent 执行、会话读取和运行读取。
5. worker 用单次票据解析身份，按 tenant 查询 PG，再按 subject 读取已确认事实；回答与来源校验在自己的执行边界复核。
6. BaseAgent 在队列准入与消费时授权，发布真实执行事件和结构化知识结果。

新增 Provider 契约用例检查身份、所有权列表、未知 Skill、无运行上下文以及撤销错误的保留；原课堂入口继续有独立回归。正式界面不再把固定演示身份当成当前用户。

## 3. 研究助手怎样调用 MCP

`ScopedResearchFactory.create(actor, runId, additionalPermissions)` 为一次运行创建执行器。模型、MCP 和原文 gateway 使用同一份 Java 权限范围；共享 MCP SSE 连接不保存某个用户作为全局身份。

正式 MCP 客户端使用 SDK 0.7.0 的异步接口。初始化握手在 PG 锁外完成；真正订阅工具请求时，组合权限在短 gate 内判断并启动 HTTP，随后在锁外等待响应。发送和等待不能用一个长期持锁的同步调用替代。SDK 的调用顺序根据本地实际字节码核实，并有真实 PG + SDK HTTP/SSE 用例检查四条发送决策和等待期间的撤销。

独立 `FormalKnowledgeMcpWorker` 把可信 ActorRef 继续传到 scoped 知识 HTTP 客户端，只发送检索业务参数。知识不可用和权限失败不会被转换成成功的空结果。MCP 模型 schema 与 wire schema 分开；模型不可见内部授权票据。

## 4. 为什么事件不能重复开启事务

普通 ResearchRunner 使用自己的发布 gate。集成进已绑定的 BaseAgent 时，调用 `runWithAuthorizedPublisher`：事件实际入队已经由 BaseAgent 授权，执行器不能再持着同一主体的授权锁调用这个发布器，否则会造成跨连接等待。

这个专用入口只给能保护实际发布边界的 Java 调用者使用。模型、工具、来源读取和最终结果仍各自复核；它不是跳过授权的通用开关。确定性用例先复现嵌套 gate，再验证受控发布不嵌套；真实业务仍需页面和模型调用取证。

## 5. 本批真实观察与边界

2026-10-29，主应用、知识 worker、正式 MCP worker 已实际启动在 loopback 8123、18132、18135，真实 PG 仍为 15432。甲工作空间使用真实 HTTP Embedding 发布了两块合成资料；随后一次知识问答返回服务未完成，按失败处理并保留截图。

增加不记录原文、密钥或异常消息的阶段诊断后，同题再次运行，真实生成和支持性判断返回两条受支持结论：VPN-401 重新登录，以及来源中的 `AURORA-A-20261029` 标记。E1 原文经实际接口重新读取。此次重跑成功不能证明首次失败根因已经修复，也不构成普遍稳定性或独立 judge 留出评测通过。

当前仅有一个可控 IAB 浏览器身份环境；顺序切换账号不等于两个独立浏览器 profile 的并发验收。该项需单独补证，不能用独立 HTTP Cookie 容器冒充。

2026-10-30 已进一步验收 MCP 发现、只读查询、乙同 ID 独立资料、真实混合检索、当前原文及模型驱动研究。发现并修复「首次发现预算永久缩短共享连接调用上限」：连接固定 SDK 审核上限，初始化与每次调用仍各自检查截止；红用例实际观测后续 9 秒请求仅收到约 4.4 秒，修复后长短请求分别遵守预算。乙研究工具 2102ms 成功，结论与乙 BOREALIS 标记一致；甲此前超时仍保留。详见 [页面证据](../images/lesson17-business-2026-10-30/README.md)。

跨标签同步随后已实现并验收共享 Cookie 下的退出失效与乙身份自动加载；不携身份的信号只促使页面重新读取服务端当前身份。后续继续只读角色、全部 SQL 与当前 Decision 同事务、Skill/多 Agent 和旧历史迁移。图谱标签本轮出现响应超时，完整视图验收与个人备份恢复校验仍待补做。第十八课任务见 [注入防护与运行隔离](18-prompt-injection-and-runtime-isolation.md)。

## 6. 面试时应能解释

- 为什么能力目录、业务参数校验、资源 owner 和实际发送授权是不同职责？
- 共享连接怎样同时承载不同租户调用，又不把身份暴露给模型？
- 为什么网络等待必须离开授权事务，已经开始发送的字节又为什么不能撤回？
- 为什么一次正确答案、一次空结果和一次服务失败需要不同验收证据？
