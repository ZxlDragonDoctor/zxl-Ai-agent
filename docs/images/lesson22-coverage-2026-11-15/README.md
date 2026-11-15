# 第 22 课保留集与覆盖率验收

在隔离的空知识库租户，用真实 qwen-plus、正式 MCP 和 PostgreSQL 运行两道保留集拒答题。abstention-4 在调用工具前失败。abstention-5 完成空检索并拒答。两次工单写入均为零。保留集其余 10 道仍无证据。

| 操作 | 结果 |
| --- | --- |
| 导入保留集报告 | [实测 2/12，通过 1、失败 1](01-holdout.png) |
| 导入真实开发集对照 | [双方均有实测证据 1/18](02-paired.png) |
| 切换英文 | [同一覆盖率和对照仍然显示](03-english.png) |

[保留集证据](holdout-evidence.json) · [保留集报告](holdout-report.json) · [开发集对照覆盖](paired-coverage.json)
