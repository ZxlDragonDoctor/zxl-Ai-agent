# 正式 Skill 的 PostgreSQL 连接中断与恢复

| 操作 | 预期 | 实际 |
| --- | --- | --- |
| 合成乙账号登录并创建空会话 | 正式 Skill 可用 | [发送前](01-before.png) |
| 切断隔离服务到数据库的连接后点击发送 | 权限无法确认时拒绝 | [HTTP 503 和失败页面](02-outage.png) |
| 恢复连接并重开同一会话 | 读取成功，没有此前被拒绝的运行 | [原会话恢复](03-original-restored.png) |
| 发起恢复后的第一个真实请求 | 如实展示终态 | [完成工具查询后模型超时](04-recovery.png) |
| 另发一轮较短的请求 | 生成待审批草稿，只含乙资料 | [真实模型复跑成功](05-post-recovery-draft.png) |
| 打开第十七课的断连证据关系 | 中文阅读卡显示来源 | [3D](06-graph-zh-3d.png) |
| 切到 2D 和一跳 | 选中状态与直接关系保留 | [2D](07-graph-zh-2d.png) · [一跳](08-graph-one-hop.png) |
| 切英文并按证据标题筛选 | 阅读卡翻译，目录只匹配证据节点 | [英文](09-graph-en-2d.png) · [筛选](10-graph-filter.png) |

[脱敏记录及两轮复跑的分别结果](../../learning/evidence/17-pg-connection-recovery-2026-11-09.json)
