# 第十九课 · 真实模型到工单回执

2026-11-13，使用隔离工作空间、合成 SSO 手册和三个测试账号。浏览器经同源代理连接当前 Spring Boot 应用、正式 MCP 和知识库服务。该流程使用 DashScope 向量模型和对话模型，数据库为 PostgreSQL 16.15 与 pgvector 0.8.6。

| 操作 | 验收结果 |
| --- | --- |
| 知识维护员导入 Markdown | [发布成功](01-knowledge-published.png)。数据库存有一条 1024 维文档分块。 |
| 员工发送问题后重新打开会话 | [保存的 v4 草稿](02-real-skill-draft.png)。MCP 成功检索导入的手册，Skill 终态为 `SUCCEEDED`。 |
| 员工提交草稿 | [返回待审批编号](03-approval-submitted.png)，与保存的运行 ID 相同。 |
| 审批员输入编号并查看 | [显示原草稿](04-approval-inspected.png)，等待人工决定。 |
| 审批员点击批准 | [显示已批准](05-approved.png)，此时页面还没有工单回执。 |
| 审批员执行 | [页面显示真实回执](06-ticket-created.png)，与数据库中的工单一致。 |
| 审批员重试执行 | [返回同一回执](07-receipt-replay.png)，数据库仍只有一张工单。 |

完整链路覆盖成功和回执重放。驳回、过期、撤权等分支的验证见[分层验收](../lesson19-handoff-2026-11-13/README.md)。模型请求发出后从服务端已保存运行继续操作，验证草稿可跨浏览器会话读取。

课程图谱生产构建通过，37 项图谱测试通过。浏览器验收了[中文 3D](08-graph-zh-3d.png)、[中文 2D](09-graph-zh-2d.png)、[一跳关系](10-graph-one-hop.png)、[英文阅读卡](11-graph-en-2d.png)与[筛选](12-graph-filter.png)。验收使用独立浏览器上下文。
