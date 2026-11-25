# 第25课：可信租户的有界运行准入

全局队列有界，只能保护进程不被无限排队拖垮；单个租户仍可能占据所有可用位置。本单元给正式多Agent登记增加每租户上限，拒绝发生在登记与调度之前。

## 身份来源和容量含义

`RuntimeSettings.tenantRunCapacity` 默认2，通过 `agent.runtime.tenant-run-capacity`（环境变量 `AGENT_RUNTIME_TENANT_RUN_CAPACITY`）配置，必须至少1。`RunRegistry` 在同一登记锁内，对 `TrustedRunContext.actor().tenantId()` 相同且尚不可回收的运行计数。身份来自服务端可信context，不读取请求context、Task、提示词或模型输出中的tenant。

同一租户的不同subject共享上限；另一个租户使用自己的限额，但仍共同受进程登记表和执行器总容量限制。旧legacy和只有boundary、没有可信context的重载保留原有全局容量行为，不伪造租户身份。

“终态”不等于“不再占资源”。已经返回取消/超时/失败的运行，只要仍持有task lease、model lease或协调线程占用，就继续消耗准入名额。只有终态且上述占用均清理后，才能允许新运行进入；历史仍可保留在登记表等待过期，不再消耗租户活动名额。未终态的排队/运行任务始终计入。

超额返回固定 `AGENT_TENANT_CAPACITY_EXCEEDED`，路径 `runs.tenant`，不携带租户ID；正式HTTP入口映射429。该429与已接受运行后发生的内部worker容量失败属于不同阶段。拒绝不新建条目、不调用模型，也不通过提前释放在途工作换取名额。

## 验证

- 默认2下，同租户不同subject第三项拒绝，另一租户可登记。
- task/model/coordinator三种残留占用分别验证：终态不释放，关闭lease/协调收尾后重入成功。
- latch同时释放4个登记线程，同租户只能接受2个。
- legacy/boundary-only行为保留；全局登记表已满仍返回原 `AGENT_CAPACITY_EXCEEDED`。
- 自定义容量1生效，0与负数配置拒绝。
- 正式带身份/CSRF HTTP用例验证新码与429。

先实跑原实现：4项登记测试中3失败；单独HTTP测试期望429实际400。随后补准入和精确映射。日志保存在忽略目录 `target/lesson25-tenant-red.log`、`target/lesson25-tenant-http-red.log`、`target/lesson25-tenant-green.log`。

2026-11-25 七类相关回归 `TenantRunAdmissionTest,TenantAdmissionHttpTest,RuntimeCapacityMetricsTest,RuntimeCapacityTest,ExecutionRuntimeIntegrationTest,ScopedMultiAgentRuntimeTest,FormalMultiAgentControllerTest` 共36项，失败0、错误0、跳过0，Maven成功。其中新增5项登记/生命周期测试和1项HTTP测试。

## 边界

这是单 `RunRegistry` 实例的租户准入隔离，不是严格公平队列、租户轮转、优先级调度，也不是多实例分布式配额。全局容量仍可能先耗尽；固定上限不会保证每个租户都有空位。进程重启后的登记容量与持久历史重放是不同机制。本单元的确定性测试不等于真实双租户HTTP负载或供应商在途取消验收。
