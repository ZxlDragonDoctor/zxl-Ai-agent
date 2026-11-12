# 第十八课：工具事件发布验收

合成资料通过正式知识入口导入 PostgreSQL。浏览器实际提交 Skill，使用真实 qwen-plus 选择工具和 MCP 查询。资料复核发现已知指令句式后拒绝该片段，页面保留诊断与失败状态。合成资料已通过正式删除入口设为 `DELETED`。

| 操作 | 预期 | 实际 |
| --- | --- | --- |
| 提交合成资料对应的问题，展开工具结果 | 只显示本地诊断，无候选正文 | [工具拒绝](01-tool-rejected.png) |
| 查看 Skill 终态 | 无待审批草稿 | [失败终态](02-skill-terminal.png) |
| 从第十八课打开工具事件关系 | 显示中文证据卡和来源路径 | [3D 图谱](04-graph-zh-3d.png) |
| 切换 2D 后查看一跳 | 保持阅读卡，显示直接关系 | [2D](05-graph-zh-2d.png) · [一跳](06-graph-one-hop.png) |
| 切换英文，再用证据标题筛选 | 卡片翻译，目录只匹配此项 | [英文](07-graph-en-2d.png) · [筛选](08-graph-filter.png) |

[脱敏运行结果](../../learning/evidence/18-tool-publication-live-2026-11-09.json)
