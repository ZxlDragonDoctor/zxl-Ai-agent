# 第十七课补充：授权判断与业务 SQL 怎样共用事务

## 先理解概念

`check()` 后再打开另一个数据库事务，中间存在撤销窗口；在授权连接里持锁、再创建业务连接，也不是同一事务。正式知识 SQL 现在显式接收 `OperationBoundary`，由 `KnowledgeDatabase.tx(boundary, phase, work)` 打开唯一业务事务，`ActorBoundary.sql` 在这份连接里调用 `AuthorizationService.sqlGate`。

它像 Spring Boot Service 的事务边界：当前权限判断、审计和受保护 Repository SQL 必须参与同一个事务。这里显式传递 Connection，不用 ThreadLocal 或模型提供的租户身份。

## 代码路径

1. `KnowledgeDatabase` 拥有连接与提交；预算版本沿用全程剩余时间和取消时关闭连接。
2. `SecurityDatabase` 在可信启动时核实自身物理数据库指纹；请求只比较候选连接，不再额外开连接或等待初始化锁。不同服务器地址/端口/数据库拒绝执行。
3. `sqlGate` 在候选连接切入授权 schema，获取当前 Decision、保留审计，再恢复业务 schema。
4. 业务 SQL 位于保存点之后。失败只回滚业务部分，延期到外层提交授权审计后再抛原失败。拒绝不执行业务回调。
5. 成功提交前一直持有当前授权事务所需锁，撤销必须等待这笔短事务提交。Embedding、生成、judge 与 reranker 的远程等待仍在事务之外。

`allowed=true` 的审计只说明当时允许该动作，**不等于业务执行成功**。连接或整笔提交失败时，也不能承诺审计已落盘。现有 JSON 会话文件仍不是 PostgreSQL 事务的一部分，不由这个改造获得跨存储原子性。

## 已运行的证据

2026-10-30，最初 4 个真实 PG 用例因缺连接感知接口明确失败。独立审查又指出首次指纹初始化可能超预算，新增启动失败红用例后改为启动时初始化；测试期间修正了 Mockito 嵌套调用与 Maven 进程内驱动注册的测试问题，没有把这些框架错误当成功能红灯。

明确选择的 8 类 **65 项测试通过，0 失败/错误/跳过**：知识 8、记忆 13、检索 9、重排边界 1、答案 18、scoped 知识 7、同事务 7、身份初始化 2。它们是一次组合回归 103 项中的通过子集；整个组合当时还有一个 Skill 空证据 fixture 错误，不把整组写成全绿。

7 个同事务用例覆盖 audit 与业务同一事务、外连接提交前不可见、SQL/Java 失败回滚及审计、search_path 恢复、撤销拒绝不执行业务、撤销等待提交，以及真实同服务器另一个数据库被拒绝。没有创建或删除其他数据库。日志为 `target/lesson17-sql-skill-publication-green-20261030.log`，精确子集见 [SQL 测试记录](../images/lesson17-business-2026-10-30/sql-tests.json)。

知识、记忆、检索与答案的 SQL 已接入新接口；现有租户/主体过滤保留。常驻服务重启、真实页面调用和课程全链路仍须在新代码上另行验收；早先页面截图不能作为这次事务改造的运行证据。

## 学习任务

- 对比 `boundary.check` 与 `boundary.sql`，说明两连接方案为何不足。
- 阅读 `AuthorizedKnowledgeTransactionPgTest` 的撤销等待与错误回滚用例，画出提交前后可见性。
- 解释「允许决策已审计」「业务 SQL 已提交」「模型已收到数据」为什么是三种不同事实。
