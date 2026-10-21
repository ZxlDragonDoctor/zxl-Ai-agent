# 第九课：任务持久化与故障恢复

发布日期：2026-10-22。前置：[第八课有限重试与幂等](08-retries-and-idempotency.md)。**本文件是可学习、可实现的任务包；本课尚未实现数据库、恢复 worker 或重启验收。**

本课目标：应用重启后仍能查询原运行和操作结果；通过数据库版本与租约控制任务领取及迟到提交；将第八课的本地工单去重迁入真实事务。围绕一个完整功能单元集中验收，可按以下六个单元逐次学习。

## 1. 先理解需要恢复什么

第七课 `RunRegistry` 保存的是 JVM 中的运行句柄，`CompletableFuture`、线程和 `ExecutionControl` 都不是可以写进数据库后恢复的业务数据。第八课的工单和 `OperationLedger` 同样只在内存中。进程重启后，它们都会丢失。

数据库保存的是业务事实和可重建输入：运行 ID、任务计划、状态、尝试、操作 ID、参数摘要、结果和事件。重启后读取这些事实，按明确策略创建新的执行上下文；不能把旧 Future “接着跑”。

Spring Boot 类比：订单状态写入数据库，定时工作者查出待处理订单、领取后执行支付；数据库事务保证领取和本地状态提交，支付请求仍要靠业务幂等。不能在调用模型的几十秒内一直持有数据库行锁。

## 2. 本课的真实起点

先读 `multiagent/runtime/RunRegistry.java` 的登记、查询和清理；读 `multiagent/execution/WorkflowRun.java` 与 `multiagent/model/TaskLifecycle.java` 的状态不变量；读 `ticket/OperationLedger.java` 的成功重放、参数冲突和不确定结果。以上路径以 `src/main/java/com/zxl/zxlaiagent/` 为根。

当前数据库仓储尚未建立。建议先用本地 PostgreSQL，通过迁移文件管理表结构；业务代码沿用现有 Java 17 / Spring Boot 3.4，使用 JDBC 实现几条关键 SQL，更容易看清事务与条件更新。选用 JPA 也可以，但要实际理解生成的 SQL。数据库配置从环境或不提交的本地文件注入。

## 3. 六个关联工程任务

### 任务一：设计业务表，明确状态权威

| 表 | 核心字段和约束 | 责任 |
| --- | --- | --- |
| `agent_run` | id、goal、status、accepted_at、deadline_at、version、created_at/updated_at | 一次用户运行的权威状态与总截止 |
| `agent_task` | id、run_id、kind、input_json、status、version、lease_owner、lease_until、fence_token | 可领取的任务及依赖输入；外键关联运行 |
| `task_attempt` | id、task_id、attempt_no、fence_token、status、started_at、finished_at、error_code；唯一(task_id, attempt_no) | 记录每次执行，保留失败历史 |
| `business_operation` | scope、operation_type、operation_id、fingerprint、status、result_json、version；三字段唯一键 | 重试间保持稳定的业务意图和去重事实 |
| `run_event` | run_id、sequence、event_type、payload_json、created_at；唯一(run_id, sequence) | 可查询的有序变化事实，后续支持事件恢复 |
| `ticket` | id、operation_id、title、description、created_at；对应操作唯一 | 本课本地业务副作用，和 operation 在同一数据库事务提交 |

表中的 scope 当前可使用固定演示用户作用域，明确不是生产认证。未来来自第 17 课的可信身份。事件 payload 不保存密钥、完整模型隐含推理或无界工具输出。

产出：一份 ER 图、一份迁移脚本、一份状态转换表；说明运行状态由数据库负责，内存只做执行资源管理。先保留现有状态机终态保护。

### 任务二：实现仓储和短事务

建立 `RunRepository`、`TaskRepository`、`OperationRepository` 的接口和 JDBC 实现。创建运行时将 run、初始 task 和“已接收”事件在一个短事务中提交。状态更新使用 `WHERE id=? AND version=? AND status IN (...)`，受影响行数为 0 必须显式识别竞争或终态冲突，不能静默当成功。

读取 API 返回数据库中的结果；JVM 中未找到句柄不能直接断言“运行不存在”。调用模型、远程工具或等待退避在事务外执行。结果回来后，再用带版本/租约令牌的短事务提交。

产出：仓储契约、事务边界图、冲突测试。继续保留 `ExecutionControl` 的单调时钟用于活进程内预算；数据库绝对截止用于恢复时重新计算剩余预算，过期运行不能重置为新的完整时长。

### 任务三：原子领取与 fencing token

worker 尝试把待执行任务更新为已领取，并设置 `lease_owner`、`lease_until` 与递增 `fence_token`。可先用单条带条件的 UPDATE 实现一个任务的竞争，再演进批量领取。不能“SELECT 看到了待执行，随后无条件 UPDATE”。

示意 SQL（具体字段和方言以迁移为准）：

```sql
UPDATE agent_task
SET status = 'RUNNING', lease_owner = :worker, lease_until = :until,
    fence_token = fence_token + 1, version = version + 1
WHERE id = :task AND version = :expected_version
  AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP));
```

领取后查询并绑定本次令牌。心跳续租和完成提交都必须核对 owner、token、状态及租约仍有效。旧 worker 在租约过期后仍可能继续执行外部动作；fencing 在本库阻止它提交旧结果，不会魔法般停止旧线程或远端服务。外部写入仍需要下游操作幂等/令牌校验或转人工处理。

产出：两个 worker 抢同一版本，只有一个领取成功；旧 token 不能提交，失败原因可查询。

### 任务四：持久化工单幂等

将第八课同操作 ID、参数摘要和返回结果迁入 `business_operation`；依靠数据库唯一约束处理同时插入。对本课同库工单，在一个短事务内插入/读取 operation、写 ticket、保存结果与事件；回滚时它们一起回滚。

不要把 operation 写入一个数据库，再向外部工单系统 POST，随后宣称“一个事务保证只创建一次”。跨系统响应不确定时，优先同 ID 查询下游；下游不支持去重则记录不确定结果、人工核对或设计补偿。账本保留期、可重放期限与客户端重试期限需要一致，不能随意清理旧 ID。

产出：进程重启后同 ID 返回原工单；参数改变仍冲突；并发请求只有一张本地工单。注意这是当前同库业务边界下的证据。

### 任务五：定义启动恢复策略

| 恢复时看到的状态 | 本课建议策略 | 不变量 |
| --- | --- | --- |
| 已完成/失败/取消 | 查询原结果，不自动重跑 | 终态不可反转 |
| 尚未开始且未过期 | 重新进入有界队列 | 不重置原截止时间 |
| RUNNING 且租约有效 | 当前实例不抢占 | 避免双领取 |
| RUNNING 且租约过期、确定只读 | 新 attempt + 新 token，有限重试 | 保留旧 attempt，不覆盖审计事实 |
| 写入已成功、响应未知 | 用原 operation ID 查询/重放 | 不换 ID 创造第二个动作 |
| 外部写入无法确认 | 标记需要核对，停止自动重试 | 不伪造成功或断言未写入 |

取消也要落数据库，由 worker 在领取、调用前及提交前检查；取消本身不能撤销已发生的工单写入。对于原运行 deadline 已过的任务，收敛为超时并记录事件。

产出：可重复执行、可审计的恢复扫描；重复扫描不会重复创建 attempt；扫描和领取同样有批量/队列上限。

### 任务六：集中故障演练与验收报告

使用临时数据库或专用本地演示库和可控模型替身。不要对真实企业库注入故障。

| 场景 | 预期与证据 |
| --- | --- |
| 写入 run 后立刻终止进程 | 重启可查询同一 run，输入与截止保留 |
| 两个 worker 同时领取 | 一次条件更新成功，另一个明确冲突 |
| 旧 worker 暂停直到租约过期 | 新 token 可提交，旧 token 提交影响行数为 0 |
| 工单事务提交后、HTTP 响应前中断 | 重启后同 operation ID 返回原 ticket，工单数 1 |
| 工单事务提交前数据库故障 | 不留下“成功 operation 却无 ticket”的半成品 |
| 恢复扫描执行两遍 | 不多建 attempt、不反转终态、事件序号唯一 |
| 停机时间耗尽原 deadline | 恢复为超时，不重新给足预算 |
| 写入结果无法确认 | 明确不确定/需核对，禁止无依据重写 |

验收报告注明数据库版本、迁移版本、模型/工具替身、故障点、期望、实际记录和未覆盖范围。当前这张表是下一课的验收要求，尚未执行。

## 4. 完成后你应能独立解释

1. 为什么存一份任务 JSON 还不算任务可恢复？哪些运行对象不该持久化？
2. CAS/version、行锁和租约分别解决什么？旧 token 为什么必须参与完成提交？
3. 数据库事务能否保护跨系统工单 POST？如何处理提交成功但响应丢失？
4. 为什么 lease 过期不等于旧 worker 已停止？如何给外部副作用设置边界？
5. 如何避免重启后每个任务都重新领取完整 90 秒预算？
6. 第九课自建恢复与第十课 Temporal 的价值、代价和幂等责任如何比较？

建议先独立完成任务一的数据模型和状态转换表，再阅读后面的实现提示。工程状态在 `/learning?node=lesson-09` 仍标记“待实现”；你的掌握状态与笔记由自己记录。
