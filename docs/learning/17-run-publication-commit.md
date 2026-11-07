# 第十七课：授权回调成功，不等于事务提交成功

在Spring Boot中，事务方法内的代码执行结束后，数据库commit仍可能失败。内存赋值不跟随数据库自动回滚。如果在授权事务回调中把运行标为completed并发出事件，而commit失败，系统就可能同时出现“页面成功”和“等待结果永远不结束”。

## 本项目的处理

`WorkflowRun.authorized`在权限门内准备报告、终态和`PrivateRunEvents.Prepared`，尚未提交的数据不会被getter或事件snapshot读到。只有`OperationBoundary.start`成功返回，才释放这批数据的可见性。对外报告与outcome从同一`Published`快照读取。

若外层commit失败，丢弃待提交事件、恢复暂存值并记录publicationFailed；编排器停止新工作，以不含goal/result/tasks的中性失败完成Future，已经开始的真实调用仍需清理。待提交事件不占有界可见队列容量，因此失败不挤掉旧历史，也不通过“恢复旧队列”复活已经淘汰的记录。

取消与终态提交由运行monitor串行，权限门内只做短暂control.cancel；不在PG事务里等远程模型。这里区分三个概念：业务已经完成、授权事务成功提交、完成结果可以被当前调用方读取。后者仍须HTTP入口重新授权。

## 先失败，再修复

`ScopedOrchestrationTest`增加回调返回成功outcome后再抛错的boundary，模拟提交失败：修复前RunHandle.result.get超时。修复后Future完成为中性失败，成功报告和对应私有事件不可见。`PrivateRunEventsTest`验证prepare/commit/discard、容量满时不挤旧历史，以及token重复操作。

8类78项回归通过，其中2项真实PG；提交失败本身由确定性boundary模拟，不是制造真实数据库磁盘/网络提交故障。原始日志保留于本地忽略目录：`target/lesson17-run-commit-barrier-red-20261107.log`、`target/lesson17-event-commit-barrier-red-20261107.log`、`target/lesson17-run-commit-barrier-green-20261107.log`。

这次屏障覆盖WorkflowRun报告、终态及其TASK_PROGRESS事件。它没有把模型请求、TOOL事件、BaseAgent全部状态和PG变成一个分布式事务，也不能回滚已经发生的外部模型费用。全局持久事件、事务outbox与断线恢复留给后续课程。
