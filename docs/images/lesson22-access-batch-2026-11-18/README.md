# 第 22 课权限组批量验收

正式 HTTP 身份认证、真实 PostgreSQL、专用合成租户。authorization-1/2/4 使用 `lesson22-access-nov18-v2`，精准归档撤权使用 `lesson22-access-nov18` 的独立 archive 员工；两者均无用户业务数据。

| 操作 | 预期与实际 |
| --- | --- |
| 所属租户读取采购文档，再从他租户读取同文档 | 200 → 404 NOT_FOUND，无资源响应体 |
| 所有人读取私有会话，再由同租户另一员工读取 | 200 → 404 NOT_FOUND，无资源响应体 |
| 读取知识列表、真实退出、旧 Cookie 重放 | 200 → 204 退出成功 → 401 UNAUTHENTICATED |
| 正式导入归档、读取、禁用会员、旧 Cookie 重放同归档 | 200 且 readOnly=true → 401 UNAUTHENTICATED |
| 阅读卡导入开发集报告 | 实测 2/18，通过 2，其他 16 项缺证据；[中文](dev-zh.png) |
| 切换保留集报告并切英文 | 实测 2/12，通过 2，其他 10 项缺证据；[中文](holdout-zh.png) · [英文](holdout-en.png) |
| 图谱切换 2D、按课程与名称筛选、阅读关联关系 | 更新后的摘要可见，关联项存在；[筛选](03-graph-filter-and-reading.png) · [关系](04-graph-relations.png) |

每题前后独立查询正式工单回执数，均为零。响应体只含错误结构，没有文档或会话字段。实际请求、身份、归属、路径、先成功后拒绝及撤权测量见 [observations.json](observations.json)。Cookie、CSRF 和密码未写入证据。

[开发集输入](dev-evidence.json) · [开发集报告](dev-report.json) · [保留集输入](holdout-evidence.json) · [保留集报告](holdout-report.json) · [复跑说明](../../learning/22-access-evaluation.md)

审批拒绝题在[前批](../lesson22-authorization-2026-11-18/README.md)独立记录。本批不调用模型，报告 model 字段表示所在验收栈配置，不是本批模型实测。全部权限题通过不等于第 22 课 30 题全量通过，也不证明任意自然语言攻击均可阻断。首次探索时使用的会员禁用列表检查没有充作归档题证据，最终由真实只读归档检查替换。
