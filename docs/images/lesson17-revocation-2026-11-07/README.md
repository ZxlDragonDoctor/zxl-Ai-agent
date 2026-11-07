# 正式事实记忆和 Skill 撤权验收

页面使用正式登录、真实 PostgreSQL、知识 worker 和 MCP 服务。两组账号均是合成账号。事实记忆与账号切换在一个浏览器依次完成，真实并发撤权由自动化测试制造。

| 操作 | 实际结果 | 截图 |
| --- | --- | --- |
| 新建知识对话并展开记忆 | 尚无可用原话，提议不可用 | [01](01-empty-source.png) |
| 发送偏好并选择已保存原话 | 选择器出现精确的聊天原话 | [02](02-source-selected.png) |
| 提议保存 | 待确认 v1 | [03](03-pending.png) |
| 查看待确认事实的召回 | 0 条 | [04](04-pending-not-recalled.png) |
| 确认并查看召回 | 中文偏好 v2 | [05](05-confirmed.png) · [06](06-confirmed-recall.png) |
| 发送英文偏好并选择为更正来源 | 可选择同类偏好的第二条原话 | [07](07-correction-source.png) |
| 提交更正、确认并召回 | 先待确认 v3，再召回英文偏好 v4 | [08](08-correction-pending.png) · [09](09-corrected-recall.png) |
| 切换英文、退出、登录另一账号并召回 | 状态以英文展示，退出移除原界面，另一账号不见前一个账号的 v4 事实 | [10](10-english-recall.png) · [11](11-signed-out.png) · [12](12-other-workspace.png) |
| 刷新 Skill 工具发现并展开详情 | MCP 准备就绪，发现 1 个工具 | [13](13-skill-discovery.png) |
| 发送 VPN 401 工单分析并查看终态 | 核验与建议失败，公开诊断为 `SKILL_DEADLINE`，无草稿发布 | [14](14-skill-running.png) · [15](15-skill-terminal.png) |
| 打开英文证据卡，再切到 2D 中文 | 两种语言可读并显示与第十七课的关系 | [16](16-graph-english.png) · [17](17-graph-chinese-2d.png) |

Java 7 类 45 项通过，21 项连接真实 PG。Skill 撤权测试使用真实执行器及队列，研究结果为替身。页面 Skill 运行使用真实服务配置，本次没有成功发布可供验收的模型建议。页面没有模拟管理员恰好在执行中的撤权点击。图谱浏览未编辑个人学习进度。

前端 25 项、图谱 37 项和生产构建通过。部分截图捕捉到召回请求切换中的上一帧，实际召回文字通过页面状态核对。重新登录后的 v4 召回另行截图并保留为 09。
