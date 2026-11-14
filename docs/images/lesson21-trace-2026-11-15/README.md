# 第 21 课跨服务追踪验收

Chrome 提交正式多 Agent 知识查询。应用、MCP 和知识 Worker 分别运行在独立 Java 进程中。应用使用真实模型，知识调用经过 MCP、Worker 和 PostgreSQL，最终返回带引用的答案。

从页面运行 ID 得到 trace ID。MCP 与 Worker 日志使用该 trace ID，Worker 的父 span 与 MCP 的 span 一致，Worker 记录 HTTP 200。自动验收同时核对运行事件序号和终态。

| 操作 | 结果 |
| --- | --- |
| 提交后打开事件流 | [持久化事件开始显示](02-stream-open.png) |
| 等待查询完成 | [终态与引用](05-synced.png) |
| 点击读取后续事件 | [响应无新增，页面无重复](06-manual-no-duplicates.png) |
| 切换英语 | [保留同一运行](07-english.png) |
| 打开课程阅读卡 | [课件与源码入口](08-learning-graph.png) |

32 项相关回归通过，包含真实 PostgreSQL 授权和传输。故障注入与完整资源清理计时仍待验收，当前追踪证据来自服务日志。
