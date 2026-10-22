# 第十课：用 Temporal 对照自建任务恢复

发布日期：2026-10-22。前置：[第九课任务持久化与恢复](09-persistence-and-recovery.md)。**本课是完整学习任务包，Temporal 服务、Java SDK、Worker 和恢复实验尚未在本项目实现。**

## 一、这课解决什么

第九课把领取、租约、尝试、截止与恢复写进了应用。接下来要判断：这些机制哪些是业务需要，哪些可以交给持久化工作流引擎？

本课沿用企业知识库与工单助手的主线，做“生成工单草稿 → 等待确认 → 创建工单 → 返回结果”的独立实验。先用可控草稿生成器，不要求付费模型调用。比较对象是相同业务场景和故障点，不用一个演示的成功去证明整体架构优于另一个。

Temporal 的 Workflow 通过事件历史重建执行状态，Worker 执行业务代码；数据库、HTTP、模型和工单写入放在 Activity 中。Workflow 的恢复不等于反复执行已完成的外部调用，Activity 的重试也不替代业务幂等。参考 [Workflow 执行与回放](https://docs.temporal.io/workflow-execution)、[Java Workflow 约束](https://docs.temporal.io/develop/java/workflows/basics)。

Spring Boot 类比：Controller 接收请求，Temporal Client 启动流程；Workflow 表达业务步骤，Activity 像调用应用 Service 的适配层，Worker 像领取任务的执行进程。Temporal Service 是另一项基础设施，不是加一个 `@Async` 注解。

## 二、六个关联工程任务

### 任务一：画清权威边界，建立最小运行环境

产出一个单独的 `lesson10-demo` 实验入口，不让第九课数据库 worker 和 Temporal 同时领取同一项任务。业务工单和操作 ID 的权威仍由业务数据库持有；Temporal 负责本实验的流程历史、调度和等待。选型结束前不迁移主项目现有的多 Agent 运行。

按照 [Java SDK 本地启动指南](https://docs.temporal.io/develop/java/set-up-your-local-java)准备本地服务、Worker 和 Client。在实际实现时选择并固定 SDK 版本，记录服务版本、namespace、task queue 和启动方式；测试依赖与 SDK 版本一致。不把示例中的 latest 或开发服务当成生产部署方案。

交付：一张边界图、一份版本清单、一套只影响本实验的启动说明。停止 Worker 时，服务和历史仍应存在；停止 Temporal Service 是另一个故障场景。

### 任务二：拆开确定性 Workflow 与有副作用 Activity

建议定义以下业务接口，具体类名可在实现时统一：

| 组件 | 输入/输出 | 责任 |
| --- | --- | --- |
| `TicketWorkflow` | 固定 operationId、问题材料、绝对业务截止 → 工单结果 | 调用草稿步骤、等待确认、决定结束 |
| `DraftActivity` | 问题材料 → 有界草稿 | 调用可控生成器，后续可换模型适配器 |
| `CreateTicketActivity` | 原 operationId、已确认内容 → ticketId | 调用业务幂等边界；查询/重放原工单 |
| `TicketWorkflowClient` | 启动请求、确认、查询 | 选择 workflowId、task queue，处理重复启动 |

Workflow 中不直接使用 JDBC、HTTP、模型 SDK、线程池或 `Thread.sleep`；随机数与时间使用 SDK 提供的回放安全方式。I/O 放进 Activity。输入和输出采用可演进的 DTO，保留长度上限和版本字段。依据：[Workflow basics](https://docs.temporal.io/develop/java/workflows/basics)、[Activity basics](https://docs.temporal.io/develop/java/activities/basics)。

交付：接口、实现、注册和一个完整流程。先完成本地可控流程，再接真实模型；模型接入不能改变业务操作 ID。

### 任务三：配置 Activity 超时、重试与业务截止

明确一次尝试的 Start-To-Close、包含重试的 Schedule-To-Close，以及确有需要时的 Heartbeat timeout。给重试设置合理上限、退避与不可重试错误类型；参数冲突和业务拒绝不应靠多试几次解决。Activity 自己调用的 HTTP/JDBC 也必须有有限超时。

工单操作的重试沿用同一个 operationId；不能把 Activity attempt number 加进业务幂等键，否则每次重试会变成新动作。workflowId、runId 与业务操作 ID 分开解释：客户端重复启动策略不等于下游工单去重。

业务截止使用输入中原有的绝对时间，等待确认使用可恢复的 Timer/等待机制；不要为了“加个总超时”随意用 Workflow timeout 中断所有长期流程。对照 [Activity timeouts](https://docs.temporal.io/develop/java/activities/timeouts) 和 [Workflow timeouts](https://docs.temporal.io/develop/java/workflows/timeouts)。

交付：一张错误分类/超时表，能解释“排队太久”“一次调用太久”“所有尝试总预算耗尽”的区别。

### 任务四：让等待、确认与取消可恢复

加入确认消息与状态查询。先用显式演示接口发送确认，不宣称已经具备生产审批；生产身份、授权、审批审计仍由后续安全课程完成。重复确认应该有确定结果，超时后迟到确认不能重新启动工单创建。

取消表示请求停止后续动作，不代表已写工单被撤销。长 Activity 要协作处理取消；在提交前后取消的两种结果应分别记录。业务写入结果未知时保留核对路径，不能伪造“取消后一定没有副作用”。

交付：等待期间停止并重启 Worker，仍能查询原流程并继续确认；终态后重复确认不改变结果。

### 任务五：用相同故障点比较两个方案

| 故障点 | 要收集的事实 |
| --- | --- |
| 草稿步骤完成后停止 Worker | 恢复后读取原结果还是重新调用？依据哪条历史？ |
| Activity 写工单成功后响应丢失 | 同 ID 重试是否返回原 ticket？数据库里几张工单？ |
| 等待确认时 Worker 离线 | Timer、确认消息和业务截止如何体现？ |
| 工单创建前收到取消 | 是否还会发起写入，状态和历史是否一致？ |
| 客户端重复启动相同意图 | workflowId 冲突策略与业务重放分别如何返回？ |
| Activity 永久错误 | 是否停止重试，错误是否适合返回用户？ |
| 实现修改后重放旧历史 | 是否保持确定性？不兼容改动怎样版本化？ |

实现阶段经用户要求后，使用 `TestWorkflowEnvironment` 做时间跳跃与受控集成检查，并补真实本地服务/Worker 重启记录。不能把内存测试服务当成真实持久化重启证据。依据：[Java 测试指南](https://docs.temporal.io/develop/java/best-practices/testing-suite)、[Workflow 版本化](https://docs.temporal.io/develop/java/workflows/versioning)。本次只发布任务，不运行这些检查。

### 任务六：写一份架构选择记录

比较第九课自建方案和 Temporal 在以下方面的责任与成本：状态权威、恢复与定时、并发控制、业务幂等、审计、代码版本演进、依赖服务、部署排障、团队维护成本。引用实际同场景结果，未测项明确写未测。

产出 ADR：主项目是否采用 Temporal、采用在哪一层、保留哪些业务数据库约束、如何避免双调度，以及迁移/回退策略。不要因为引入了框架，就删掉 operationId、鉴权和业务状态校验。

## 三、集中验收标准

- 一个 workflowId 的过程可通过历史解释；一个业务 operationId 不重复创建工单。
- Worker 重启后继续原等待或后续步骤；业务截止不因重启而重新计算为完整时长。
- Workflow 不直接执行不确定 I/O；Activity 有显式超时、错误分类和幂等责任。
- 取消、永久错误、重复启动和迟到确认具有明确结果。
- 至少一份旧历史回放/版本演进记录，以及自建方案的对照 ADR。
- 报告区分源码、编译、受控检查、真实服务重启、真实模型和生产部署。

## 四、面试应能独立回答

1. Workflow 和 Activity 为什么要分开？回放究竟重放什么？
2. Temporal 已经能恢复，工单服务为什么还要业务幂等？
3. Start-To-Close 与 Schedule-To-Close 各管哪段时间？
4. 为什么 Activity 重试不能重新生成 operationId？
5. 如何修改有旧执行历史的 Workflow？
6. 对这个项目，什么时候自建 JDBC 恢复足够，什么时候值得引入工作流引擎？

官方资料查阅日期为 2026-10-22。本文是结合本项目的学习设计，不代表仓库已经安装 Temporal 或通过其恢复验收。读完后先画任务一的权威边界，再逐项实现；图谱中第十课保持“待实现”。
