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

**供应函数前后的 check 不等于实际网络发送已原子授权。** 下一单元须把可信 scope 接到已有 DashScopeAgentStreamer 的实际订阅 gate，并把 ResearchAgent 接入 ScopedResearchFactory；不能把阻塞 ChatClient.call 整体放入 PG gate。

正式 submit 的资源 owner 注册、状态/events/cancel 的归属过滤、Orchestrator.pendingTasks 的跨运行键、全局 observer/report 隔离，以及旧 Controller/harness 的封闭仍未完成。现有前端和旧演示运行不能视为正式多租户业务通过。本课保持进行中，不据这6个用例把多 Agent 授权标为完成。
