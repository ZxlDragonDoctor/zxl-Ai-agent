# 第25课：区分运行入口与工作线程的容量拒绝

运行队列和工作线程队列是两层资源。运行已获准进入协调池，不代表随后每个阻塞I/O都能进入工作池。只统计入口拒绝会漏掉内部饱和，因此本项目分别保留两类计数。

## 源码与字段

`src/main/java/com/zxl/zxlaiagent/multiagent/runtime/ExecutionRuntime.java` 的 `metrics()` 新增以下进程内字段；原字段和拒绝行为保持兼容。

| 字段 | 含义 |
| --- | --- |
| `runRejections` | 协调池 `dispatch()` 被执行器拒绝的累计次数；与原 `rejected` 使用同一计数源 |
| `workerRejections` | 工作池 `async()` 被执行器拒绝的累计次数；不包含运行入口拒绝 |
| `workerQueuePeak` | 成功提交后观测到的工作队列深度最大值；不是活动worker数量 |

这些值的生命周期是一个 `ExecutionRuntime` 实例，应用重启会重置。执行器关闭后提交导致的拒绝也属于相应计数，不自动等同于业务负载过高。取消或队列超时清除不是新提交拒绝，不增加拒绝计数。读数是并发采样，不构成全字段原子快照；队列峰值是提交后采样的观测峰值，快速出队可能使其小于实际瞬时峰值。没有租户、任务、正文或目标标签。

## 验证方法

新增 `RuntimeCapacityMetricsTest` 使用1个协调线程/1队列、1个工作线程/1队列。latch明确占用线程，填满队列后提交第三项，分别断言拒绝来源。工作队列用受控单调时钟触发100ms排队截止，另一个场景明确取消绑定运行；移除后再次提交并执行成功，计数和峰值不膨胀。测试不依赖模型或数据库，不以sleep碰运气填队列。

先运行新增测试，3项均因缺少计数字段断言失败（0错误/0跳过），随后补最小实现。相关回归命令：

```powershell
.\mvnw.cmd -o -B -ntp "-Dtest=RuntimeCapacityMetricsTest,RuntimeCapacityTest,ExecutionRuntimeIntegrationTest,ScopedMultiAgentRuntimeTest" test
```

本单元是容量观测和队列回收行为的确定性验证。正式HTTP负载、模型供应商限额、吞吐量和P95结论需要独立负载实验；这些计数本身不构成生产容量承诺。运行入口的拒绝可映射HTTP429，已接受运行内部的工作池拒绝应从终态错误读取，不能只数HTTP状态。

2026-11-25 本地执行上述四类回归：21项，失败0、错误0、跳过0，Maven成功。红/绿日志保存在本地忽略目录 `target/lesson25-runtime-red.log`、`target/lesson25-runtime-green.log`；这些本地结果不等于干净云端构建或真实模型验收。
