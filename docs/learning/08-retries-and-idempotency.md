# 第八课：有限重试与工单幂等

日期：2026-10-22。前置：[第七课实现记录](07-implementation.md)。本课工程已实现，证据和限制见[验收记录](08-implementation.md)。下一课：[任务持久化与恢复](09-persistence-and-recovery.md)。

本课围绕一个完整业务动作：创建“VPN 无法连接”工单。先解释机制，再沿代码观察受控故障；内容按关联单元阅读，无须逐行断点。

## 1. 为什么收到超时之后不能直接再创建一张

一次请求有两件事：服务端完成写入，客户端收到响应。前者成功，后者也可能因为网络中断失败。客户端只知道“没有收到”，无法单凭超时判断有没有创建工单。若每次重试都生成一个新操作 ID，下游会把它当成新的动作。

重试处理“暂时不可用，稍后也许能成功”；幂等处理“同一个业务意图再次到达，返回原来的结果”。第一次点击创建时确定 `operationId`，之后该动作的所有重发沿用它；用户确实要另建工单时才创建新 ID。

Spring Boot 类比：Controller 接收订单请求，Service 判断同一订单号是否已处理，Repository 保存订单。重试是调用方的有限循环；幂等约束在真正拥有写入的服务端执行。只有客户端缓存成功响应，不能防止响应丢失后的重复写入。

## 2. 本课的调用链和职责

```text
LearningTicketController（仅 lesson8-demo profile）
  → TicketCommand（规范参数 + 版本化摘要）
  → TicketService
  → RetryExecutor（最大尝试次数 + jitter + 共享截止/取消）
  → TicketGateway
  → InMemoryTicketGateway
  → OperationLedger（同 ID / 同参数重放；同 ID / 异参数冲突）
  → 本地 Map 工单写入
```

这是无模型、本地单用户实验。没有给整个 Agent 工作流套重试，也没有将无审批工单写入注册为模型工具。现有多 Agent 的模型客户端继续关闭隐式 SDK 重试，避免多层重试相乘。工单的可信身份、审批和真实系统接入分别留到第 17、19 课。

| 文件 | 阅读重点 |
| --- | --- |
| `multiagent/runtime/retry/RetryFailure.java` | 只将已知、安全的暂时故障标记为可重试，未知异常直接抛出 |
| `multiagent/runtime/retry/RetryPolicy.java` | `maxAttempts` 包含第一次调用；指数退避设置上限，再做 full jitter |
| `multiagent/runtime/retry/RetryExecutor.java` | 每次尝试与每段等待检查同一个 `ExecutionControl`；等待可中断 |
| `ticket/TicketCommand.java` | 参数去首尾空白、长度限制、含版本和长度前缀的 SHA-256 摘要 |
| `ticket/OperationLedger.java` | 原子判重、结果重放、永久错误保留、不确定结果禁止自动再写 |
| `ticket/InMemoryTicketGateway.java` | 锁内只有短本地写入；账本与工单受同一 JVM 的串行边界保护 |
| `controller/LearningTicketController.java` | HTTP 返回原工单、409 冲突或明确错误；演示容量 1000，默认总预算 5 秒 |

源码路径均以 `src/main/java/com/zxl/zxlaiagent/` 为根。

## 3. 六个关联学习单元

### 单元一：定义可重试边界

先观察错误，而非先写 `catch (Exception)`。本课 `RetryFailure` 分成明确瞬态和永久失败；未知运行时异常不重试。参数不合法、操作 ID 参数冲突属于永久错误。适配器只有在确认操作尚未发生，或下游支持同操作 ID 去重时，才能声明网络失败可重试。

独立练习：列出查询知识库、创建工单、发送通知三个动作，逐个说明“响应丢失后能否安全重试”及其依据。不能只回答“GET 可以，POST 不行”，业务契约才是判断依据。

### 单元二：有限退避，共享总预算

尝试上限默认 3 次；首次失败后上限为 100 毫秒，下次为 200 毫秒，增长到 1000 毫秒封顶。每次实际等待随机取 `[0, 上限)`，使同时失败的调用不要整齐地再次冲击服务。

总预算包含调用和退避。准备等待时若剩余预算不足，返回 `RETRY_BUDGET_EXHAUSTED`；等待中每隔最多 25 毫秒检查取消/截止，中断时恢复线程中断标记。重试不会延长第七课的运行截止时间；它也不能自行终止一个已经阻塞的 HTTP 调用，真实适配器仍需连接/请求超时。

独立练习：把固定随机源从 `0.5` 改为 `0.9`，在可控时钟测试里预测退避和预算不足的结果。解释为什么“3 次，每次 30 秒”不能被当作 30 秒总预算。

### 单元三：稳定操作 ID 和参数指纹

`TicketCommand` 规范化标题和描述；摘要包含规范版本、字段长度与内容，避免简单拼接歧义。同一 `operationId` 与同一摘要返回原 `Ticket`；摘要不同返回 `OPERATION_CONFLICT`，不偷偷覆盖旧动作。

操作 ID 在本课是单用户演示空间中的 ID。未来身份接入后，去重唯一键必须包含可信租户、业务动作类型及操作 ID；这些值不能直接信任模型自报。摘要不包含凭据或本地配置。

独立练习：同一个 ID 先创建 VPN 工单，再修改标题提交，核对冲突和工单数；另用新 ID 提交修改后的标题，核对它是第二个业务动作。

### 单元四：让并发重复到达只写一张

账本将“查旧记录、校验摘要、写入工单、保存结果”放在同一进程的短临界区。并发请求等待这个本地边界，后续读取原结果。这里没有远程 I/O，不能照搬为“用一个 Java 锁包住跨系统 API”。

账本在开始写入前保留不确定占位；未知异常后保留 `OPERATION_UNCERTAIN`，禁止自动重新写入。已知永久失败也保留；只有确定尚未写入的瞬态失败才允许释放占位并重试。

演示账本达到 1000 条后拒绝新操作，同时仍允许旧操作重放。不自动删旧记录，是为了避免同 ID 在淘汰后被再次创建；持久化、保留期和容量治理在下一课设计。JVM 崩溃或重启会丢失账本和工单。

### 单元五：响应丢失实验

`TicketReliabilityTest.lostResponseRetriesSameOperationWithoutDuplicatingWrite` 的下游先真实调用本地工单写入，再在返回途中抛出一次 `RESPONSE_LOST`。`TicketService` 用同一 `TicketCommand` 重试，第二次得到原工单。

核对三个证据：调用次数为 2、工单数量为 1、重放工单 ID 不变。这个故障只在测试适配器注入，普通 API 不向用户暴露制造失败的参数。取消不能回滚已提交写入：若写入后响应前取消，应核对原操作结果，重发时仍用原操作 ID。

### 单元六：集中验收与复盘

运行专项测试，而非真实付费模型：

```powershell
mvn -q '-Dtest=TicketReliabilityTest,TicketApiTest,ExecutionControlTest' test
```

预期检查：重复并发、参数冲突、永久错误不重试、瞬态错误有上限、deadline/取消后没有第二次尝试、等待中断、响应丢失不重复写、未知结果保留、容量拒绝仍可重放。当前实际结果见[验收记录](08-implementation.md)。

## 4. 用真实 HTTP 观察本地业务变化

在现有能正常启动的本地配置基础上，额外激活 `lesson8-demo` profile（例如设置 `SPRING_PROFILES_INCLUDE=lesson8-demo`），重启后端。不要覆盖当前加载凭据的本地 profile。此开关只开启课程工单接口，业务状态仍在内存中。后端接口默认前缀请以当前本地配置为准，以下按 `http://localhost:8123/api` 举例。

```powershell
$ticketBase = 'http://localhost:8123/api/ai/learning/tickets'
$ticketBody = @{ operationId = 'vpn-demo-001'; title = 'VPN 无法连接'; description = '已尝试重启客户端' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri $ticketBase -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($ticketBody))
Invoke-RestMethod -Method Post -Uri $ticketBase -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($ticketBody))
Invoke-RestMethod -Method Get -Uri $ticketBase
```

两次 POST 应返回同一 `ticket.id`，GET 只增加一张工单。每个 HTTP 请求有自己的尝试次数，因此普通重复请求通常都显示 `attempts=1`；它不表示“这个操作从未在之前请求执行过”。使用同 ID 修改标题应返回 HTTP 409 / `OPERATION_CONFLICT`。本课未新增独立前端工单页面；学习图谱提供完整原理、源码与验收入口。

## 5. 面试练习

1. 请求超时后为什么无法判断有没有写入？你用什么业务证据恢复判断？
2. 重试应该放在模型调用、整条工作流还是具体业务操作？多层重试次数如何相乘？
3. 操作 ID 的作用域和参数摘要如何设计？同 ID 不同参数为何返回冲突？
4. Java 锁为什么无法解决多个实例的幂等？服务重启会丢失什么？
5. 幂等和“恰好执行一次”有什么区别？旧 worker 迟到、外部调用不确定时如何处理？

表达时使用“问题 → 方案 → 本课受控证据 → 当前限制 → 下一课演进”。无需把自动化通过描述为个人已掌握；请在 `/learning?node=lesson-08` 记录自己的理解和独立练习结果。
