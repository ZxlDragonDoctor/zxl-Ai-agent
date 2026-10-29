# 第十七课：正式业务首轮观察（2026-10-29）

这些是实际操作的阶段证据，不代表全课完成。逐步动作、预期与结果见 [observations.json](observations.json)。

| 范围 | 结果与截图 |
| --- | --- |
| 匿名、登录与目录 | [登录页](01-anonymous-login.jpg)、[正式目录](02-authenticated-directory.jpg) |
| 甲真实知识接入 | [HTTP 1024D / PG](03-tenant-a-storage.jpg)、[导入进行中](04-tenant-a-import-pending.jpg)、[发布完成](05-tenant-a-import-published.jpg) |
| 默认 Agent 缺陷 | [未开放类型仍为默认](06-invalid-default-agent.jpg)，补选择规则与测试后[修复状态](07-formal-default-fixed.jpg) |
| 甲真实知识回答 | [进行中](08-knowledge-answer-running.jpg)、[首次服务失败](09-knowledge-answer-failure.jpg)、[同题第二次支持性回答](10-knowledge-answer-supported.jpg)、[重新读取当前原文](12-current-source-readable.jpg) |
| 正式 MCP | [发现就绪](13-mcp-discovery-ready.jpg)、[甲只读关键词查询](14-mcp-keyword-a.jpg) |
| 真实研究失败 | [工具调用超时](15-research-tool-deadline.jpg)、[模型参数与受控错误](16-research-timeout-details.jpg) |

11 号图只记录打开原文操作，原文位于折叠区域下方；12 号滚动后才是可读原文证据。知识问答首轮失败根因未确认，后续成功不能证明它已被修复。研究超时后测试复现了共享连接预算缺陷，修复与重跑见 [次日证据](../lesson17-business-2026-10-30/README.md)。

所有资料与账号均为本地合成验收数据；密码、服务票据与 API 密钥没有写入证据。使用真实 PG、HTTP Embedding、qwen-plus 生成及同模型支持性判断；不是独立 judge 留出评测，也不代表生产质量门槛通过。
