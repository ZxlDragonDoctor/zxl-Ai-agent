# 单参数对照：更长队列不等于更强处理能力

`QueueCapacityComparisonTest`运行真实`ExecutionRuntime`协调执行器，不使用模型、HTTP或数据库夹具。固定1个协调线程、6项提交、相同工作逻辑和其他默认配置；只有`runQueueCapacity`按1→4→1变化。首项用latch明确占用线程，在释放之前提交其余5项，使排队状态可重复，不用随机sleep制造饱和。

| 队列容量 | 提交数 | 执行线程 | 立即拒绝 | 释放后完成 |
| --- | --- | --- | --- | --- |
| 1 | 6 | 1 | 4 | 2 |
| 4 | 6 | 1 | 1 | 5 |
| 恢复为1 | 6 | 1 | 4 | 2 |

每阶段在latch释放前断言完成数为0、活动协调线程为1和实际排队深度等于容量；释放后等待执行器结束，断言完成数、队列归零。恢复原参数后结果相同。结论是只改变队列可以改变本次有限突发的接收与拒绝数量，没有增加线程或证明服务速度变化；排队延迟、P95和吞吐未测量，不作相关承诺。

2026-11-25本地执行：`mvnw.cmd -o -B -ntp -Dtest=QueueCapacityComparisonTest,RuntimeCapacityTest,RuntimeCapacityMetricsTest test`，6项通过，0失败/错误/跳过。结果JSON由实际测试输出，归档于[实验数据](../images/lesson25-capacity-2026-11-25/25-queue-comparison.json)。这是本地组件的因果对照，和前述真实模型小负载分别验收；不能把它代称真实HTTP单参数性能基线。云端尚需重新验证新增测试。
