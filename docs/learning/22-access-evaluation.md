# 第 22 课：把权限要求变成可复核的评估

Spring Boot 的权限测试不应只证明“某个请求返回错误”。先证明资源存在且归正确的人，再用错误身份请求同一资源，最后确认没有资料泄漏或业务写入，才是一次有效的权限验收。

本项目将这组检查放在 Java 身份服务、正式 HTTP API 和独立 PostgreSQL 观察之间。没有让模型判断自己是否越权，也没有把未找到的虚构 ID 当成权限隔离证据。

| 固定案例 | 前置成功 | 拒绝证据 |
| --- | --- | --- |
| authorization-1 | 所属租户读取已发布采购文档 200 | 他租户同路径 404 NOT_FOUND，不暴露文档 |
| authorization-2 | 所有人读取刚创建的私有会话 200 | 同租户另一员工同路径 404 NOT_FOUND |
| authorization-3 | 真实草稿已提交待审批 | 另一员工完整审批 POST 返回 403 ACTION_DENIED；见前批记录 |
| authorization-4 | 同一会话知识读取 200 | 真实退出后，保留旧 Cookie 重放同路径返回 401 |
| authorization-5 | 正式导入的只读归档读取 200、readOnly=true | 会员禁用后，同 Cookie、同归档路径返回 401 |

404 用于隐藏他人资源是否存在；401 用于已经失效的认证；403 用于已认证员工没有审批能力。它们都可投影为评估中的 denied，但不能省略各自的场景证明。

## 代码入口与复跑

`scripts/lesson22/project-access.mjs` 校验请求方法、路径、身份归属、先成功后拒绝、无资源响应体、前后零回执。`project-access.test.mjs` 覆盖错误资源、相同身份、错误路径、泄漏响应、缺少撤权和非归档等反例。

使用本机专用验收服务、真实 PostgreSQL 和全新的 `lesson22-access-*` 租户。运行前设置数据库环境变量、`LESSON22_ACCESS_TENANT`、`LESSON22_ORIGIN`、`LESSON22_ACCESS_PASSWORD`、`LESSON22_PSQL` 及 PG 客户端变量。密码仅放环境变量，不写入提交、命令参数或评分报告。

1. 用应用类路径运行 `scripts/lesson22/Lesson22AccessSeed.java <新租户>`，离线建立合成测试身份。
2. 运行 `node scripts/lesson22/run-access.mjs`，正式创建私有会话和采购文档，采集 1、2、4 题。它会撤销自己创建的 revoked 测试会话，不能复用用户身份。
3. 用同一类路径运行 `scripts/lesson22/Lesson22ArchiveSeed.java <租户> <当前服务会话目录> <全新fixture目录>`。它只写入唯一合成归档，通过正式 manifest 和 LegacyHistoryImporter 导入并备份。
4. 运行 `node scripts/lesson22/run-archive-access.mjs <fixture目录>/archive.json`，读取归档、禁用该合成归档员工的会员资格，再重放读取。该会员资格会保持禁用，重复测试应建立新的专用租户和 fixture。

采集文件写到 `target/`，不包含 Cookie、CSRF 或密码。1、2、4 题输出为 observations/evidence；归档脚本输出单题 observation，使用 `projectAccess` 投影后按固定案例 split 评分。不同知识版本或变体不能直接拼成提示词改善结论。

本批四题真实验收通过，开发集与保留集分别记录；结合前批审批拒绝，授权组五题均有场景证据。这里评估的是服务端权限边界，没有新增模型调用，不等同于全部自然语言越权攻击已覆盖。[逐项记录、评分与截图](../images/lesson22-access-batch-2026-11-18/README.md)
