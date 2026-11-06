# 第十七课：多 Agent 运行边界的第一单元

## 概念

模型给出的 task.id 是业务引用，不是跨运行的安全身份。两个计划可以拥有相同 ID；Task 字段也可能变化。共享执行器若用它作全局 map 键，会让一个运行的控制绑定覆盖另一个运行。

## 本批代码

`ExecutionRuntime` 的任务绑定和排队记录改用内部 Task 对象身份键：equals 比较同一对象、hash 用 identityHashCode，不依赖模型 ID 或 Task 的可变 equals/hashCode。不同运行的同 ID 任务不再互相覆盖，修改 ID 也不丢控制。

`RunRegistry.Entry` 显式保存 Java 传入的 `OperationBoundary`。旧课堂 register 重载保留 unrestricted；正式集成必须调用带边界重载，不能把它当作正式身份已经自动装配。

模型容量等待、调用前与结果返回后检查当前边界。`modelCall` 一开始捕获 scope/control；调用中解绑不能使结果发布检查退回 unrestricted。远程等待没有包进长 PG 事务。

## 已验证与未完成

专项用例先复现缺少注册 API；新增同 ID、可变 ID、解绑降级用例后，6 项中有 3 项按预期失败，再修实现并重跑。相关容量、执行器整合与消息总线回归的实际数量见 `17-implementation.md`。这些是本机受控执行证据，没有实际付费模型或页面截图。

2026-11-06 最终选择 4 类19项测试通过，0失败、错误、跳过（专项6项，其余13项为相关回归），[精确范围](evidence/17-multi-runtime-tests-2026-11-06.json)。日志为 `target/lesson17-multi-runtime-final-20261106.log`。首次增量编译采到了旧3项快照，因此补充显式重新编译，确认4项中的同ID失败及新增6项中的3个行为失败；不把旧快照数字冒充新用例通过。

**供应函数前后的 check 不等于实际网络发送已原子授权。** 后续补充的模型传输单元已将同一可信 scope 接到 DashScopeAgentStreamer 的实际订阅 gate，见下文；ResearchAgent 接入 ScopedResearchFactory 仍待完成，不能把阻塞 ChatClient.call 整体放入 PG gate。

## 第二单元：上下文与实际模型传输

旧 register 重载明确标为课堂运行；显式传入 boundary 的重载标为 scoped。ExecutionScope 捕获同一 Entry，容量等待、模型租约、取消对象、实际发送和返回检查均使用这份快照，不因两次查表之间解绑而失去原模型租约。正式模式下工作 Agent 必须具有 scoped 上下文，缺失时返回 SCOPED_EXECUTION_CONTEXT_REQUIRED，不进入旧 ChatClient。

Planner、Code、Research、Supervisor 由 MultiAgentConfig 手动创建，所以显式注入 streamer 和正式模式要求；Orchestrator 只调度，不继承工作 Agent 基类。普通模型步骤传入空工具表，拒绝模型意外工具调用和空答案。旧课堂调用保持兼容。

实际订阅由原授权 boundary.start(MODEL_DISPATCH) 包裹短 ExecutionControl.admit，再启动订阅。若取消在权限 gate 内发生，订阅动作不执行；取消在订阅已经获准后发生，流会停止等待，但这不等于撤回已经发出的远程请求。远程响应等待位于两种锁之外，逐帧及最终返回仍检查原边界与控制。

该单元初次交付时，ResearchAgent 对 scoped 运行返回 SCOPED_RESEARCH_INTEGRATION_PENDING（503）。后续已补[可信研究执行单元](17-private-research.md)：具备上下文和工厂时使用正式v2路径及私有队列，缺失时仍拒绝。正式编排入口与完整业务验收仍未完成，不能由该单元推断整课完成。

2026-11-06：新增专项9项（含实际本机 HTTP 成功/拒绝发送），相关5类24项，合计6类33项通过，0失败、错误、跳过。[精确测试范围](evidence/17-model-transport-tests-2026-11-06.json)。撤掉修复后，原租约丢失与研究降级两项均失败；取消 gate 竞态用例也先失败再修复。本机 HTTP 使用合成响应，不是付费模型；本批没有重跑真实 PG 授权或整条正式多 Agent 业务，运行中的旧 JVM 也不会因编译自动更新。

正式 submit 的资源 owner 注册、状态/events/cancel 的归属过滤、Orchestrator.pendingTasks 的跨运行键、全局 observer/report 隔离，以及旧 Controller/harness 的封闭仍未完成。现有前端和旧演示运行不能视为正式多租户业务通过。本课保持进行中，不据这6个用例把多 Agent 授权标为完成。
