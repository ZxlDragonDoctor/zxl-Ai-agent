# 第 21 课指标导出验收

在开启本地 JMX 导出的正式工作台提交 3 秒预算的知识查询。真实模型开始返回流式数据，随后预算耗尽。独立客户端连接应用 JVM，读取 `com.zxl.agent:type=ModelMetrics` 的 `Snapshot`。

| 计时器 | 结果 | 次数 | 总耗时 |
| --- | --- | ---: | ---: |
| agent.model.queue | timeout | 1 | 23.49 ms |
| agent.model.admitted | timeout | 1 | 2886.85 ms |

PostgreSQL 的终态事件为 `RUN_DEADLINE_EXCEEDED`。终态日志记录当时有一个活跃任务，模型许可已归还。

[打开事件流](02-stream-open.png) · [超时终态](05-synced.png) · [补读无重复](06-manual-no-duplicates.png) · [英文界面](07-english.png)
