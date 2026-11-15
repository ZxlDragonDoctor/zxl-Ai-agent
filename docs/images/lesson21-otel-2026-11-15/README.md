# 第 21 课 Collector 验收

工作台通过真实模型和 MCP 查询知识库，PostgreSQL 保存终态。Collector 0.147.0 收到同一 trace ID 下的三个 span。逐一比对确认 Worker 的父 ID 是 MCP 的 span ID，MCP 的父 ID 是应用调用端的 span ID。

| 操作 | 结果 |
| --- | --- |
| 展开事件流 | [运行 ID 和首批事件](02-stream-open.png) |
| 等待完成 | [带引用的终态](05-synced.png) |
| 点击补读 | [无重复事件](06-manual-no-duplicates.png) |
| 切换英语 | [保留当前运行](07-english.png) |
| 打开课程阅读卡 | [检查第 21 课入口](08-learning-graph.png) |

13 项后端测试通过，覆盖导出结构、错误状态、追踪上下文和 MCP 传输。
