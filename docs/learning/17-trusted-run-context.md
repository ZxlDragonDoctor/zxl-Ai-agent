# 第十七课：可信运行上下文与私有事件

## 先理解概念

用户问题与模型计划是业务数据，不能决定运行属于谁。`TrustedRunContext` 相当于异步执行时显式携带的 Principal：保存服务端建立的 ActorRef、同一运行 ID 和本地私有事件队列，不从 Task.input 或提示词推导。

## 对应源码

- `multiagent/runtime/TrustedRunContext.java`：字段非空，运行 ID 非空白；不接收任意 Consumer。
- `RunRegistry`：五参 register 在登记前验证 context.runId 等于当前 WorkflowRun.id。旧重载不自动获得身份。
- `ExecutionRuntime.ExecutionScope`：捕获 Entry，同一上下文在解绑后仍可供原调用使用。
- `PrivateRunEvents`：只做本地存储，无用户回调和远程等待，避免在任务完成后因观察者异常出现 Future 失败、快照成功的不一致。

队列默认最多256条，可设1–1024；满后淘汰最旧记录。snapshot 返回独立、结构不可变的列表，内部 AgentEvent 仍是现有可变对象。它是内存历史，**不是数据库事件表、可靠重放或跨进程广播**。后续正式读取接口仍须实时核验身份和运行归属，不能直接公开 snapshot。

## 验证与边界

可信上下文4项、队列/API约束4项均包含在本轮90项通过的自动化范围内。上下文和队列新增API先出现编译失败，再实现；记录分别在 `target/lesson17-trusted-context-red-20261106.log`、`target/lesson17-research-admission-red-20261106.log`。后者也包含研究准入API缺失，不能把整份日志理解成单一队列行为失败。

正式运行入口尚未开放；这些 Java 类型不等于身份自动可信。入口必须先注册真实运行 owner，并固定选择 agent.execute、knowledge.read、run.readOwn 等所需权限，再绑定 ActorBoundary 与上下文。本批没有修改个人学习掌握状态。
