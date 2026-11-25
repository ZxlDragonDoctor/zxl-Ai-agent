# 第26课：故障演练与恢复——工程实现记录

本课按独立功能单元持续补齐，不能将已有故障测试或任务包直接当作综合演练完成。

## 已有能力与本次补齐

第22课 `Lesson22ToolFaultPgTest` 已有真实PG授权和SDK SSE故障分类测试，覆盖MCP断开、受控lookup超时、响应上限；`Lesson22RemainingPgTest` 覆盖实际SSE上的畸形业务JSON与总期限跨越。`Lesson22BudgetPhasesTest` 使用受控Java模型和时钟验证规划/工作排队/汇总阶段的总期限及迟到结果不得发布。`FormalWorkerProcessPgTest` 已验证独立正式Worker进程启动、双租户资料隔离和内部ticket重放拒绝。这些分别说明协议、控制或启动行为，不能合并声称完整故障恢复通过。

本次补齐 `FormalWorkerRecoveryPgTest`：真实PostgreSQL、实际HTTP和独立的正式Worker JVM，按以下次序执行一个恢复闭环。

1. 生成测试专用内部服务token，在系统分配的loopback端口启动 `FormalKnowledgeWorker`。
2. 使用生产 `ScopedKnowledgeClient` 写入两个独立租户的同ID资料，读取并验证互不泄漏。
3. 停止且等待本测试自己启动的Worker进程退出；同一个gateway请求明确返回 `KNOWLEDGE_WORKER_UNAVAILABLE` / 503，不读取内存替代品。
4. HTTP停机时直接核对PG原资料仍在。
5. 同端口启动新Worker进程，验证PID不同；复用原gateway（其中HttpClient也未替换），读回旧资料并保持租户隔离。
6. 两个租户分别提交新资料，经HTTP与PG直接读回确认恢复后仍能写入。

只停止测试所拥有的子进程；原课堂运行组、实验库、孤立账户与历史证据不变。测试使用独立 `lesson26_recovery` 数据库及每次随机租户，结束清理自身夹具。服务端确实走正式Worker及PG路径；embedding明确为 `DemoKnowledgeEmbedding`，不调用供应商embedding或模型，不能冒充真实HTTP embedding验收。该单元不是容器/主机故障、PG断连、LLM效果或生产SLA测试。

## 执行与证据

2026-11-26，先补恢复测试再运行：首轮1项通过，失败0、错误0、跳过0，Maven成功（`target/lesson26-worker-recovery-first.log`）。已有生产客户端直接支持恢复，因此本次是新增工程回归验证；没有伪造生产bug或为凑改动修改客户端。

```powershell
# 显式配置专用 PostgreSQL 后执行，凭据不写进课程
.\mvnw.cmd -o -B -ntp -Dtest=FormalWorkerRecoveryPgTest test
```

首轮Worker日志在本地忽略目录 `target/lesson26-worker-recovery-before.log` 和 `target/lesson26-worker-recovery-after.log`；日志可能含运行信息，不把整个target打包提交。测试断言不打印token、请求正文或业务原文。

相关九类回归：`FormalWorkerRecoveryPgTest,FormalWorkerProcessPgTest,ScopedKnowledgeClientPgTest,ScopedKnowledgeClientContractTest,WorkerAuthenticationPgTest,FormalKnowledgeMcpPgTest,ResearchFailureClassificationTest,Lesson22RemainingPgTest,Lesson22ToolFaultPgTest`，共43项通过，失败0、错误0、跳过0，Maven成功。日志 `target/lesson26-worker-recovery-regression.log`。其中包含纯Java契约/分类与真实PG/HTTP/SSE测试，不能把43项全部称为真实模型请求。

## 当前边界

尚未用本课新版本做真实供应商模型等待故障、完整主服务到MCP到HTTP embedding的停机恢复页面验收；也未完成跨主机、PG进程故障、预算UNKNOWN账务核对和综合恢复时间目标。已有旧证据必须注明当时版本与环境，不能自动迁移为本课已验证。后续演练还需要区分客户端终态、后台工作清理与供应商在途状态。
