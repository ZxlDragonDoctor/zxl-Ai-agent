# 第25课：准入拒绝后的预算账户补偿

双租户真实实验发现：两个接受的运行完成并结算，但库中有三个预算账户。第三个来自一次429租户准入拒绝，没有调用记录、预留或支出。原始证据保留在 `18-tenant-pg-observation.json`；修复不会删除这条旧数据冒充新版本已验证。

## 为什么会残留

`ScopedMultiAgentService.submit` 先注册服务端run归属、调用 `ScopedModelBudgetFactory.initialize` 固定运行创建日的预算周期，再执行内存登记准入。准入拒绝后，原 `AuthorizationService.discardUnstartedRun` 只删除归属。账户按随机run UUID创建，没有TTL，因此重复拒绝可以持续累积孤立账户。共享period按tenant与UTC日期复用，不是每次拒绝一个period。

## 最小修复与守卫

保留initialize顺序和创建日周期语义。失败补偿在同一个PG事务内先锁精确tenant/subject/run的 `resource_owner`，没有匹配归属直接返回；再锁预算账户。账户存在时，accepted_calls、reserved_tokens和spent_tokens必须全为0；无任何call记录（包括RELEASED审计），也无durable run，才允许删除空账户及归属。存在任何启动/审计证据时两者均保留。旧无usage账户路径仍可清理未启动归属。共享period始终保留。

不能仅看reserved=0就删：SETTLED和RELEASED也可能零预留，仍是重要审计。删除账户有级联call外键，必须在删除之前完成无call守卫，不能让级联掩盖审计丢失。补偿是可信服务端操作，不重新依赖可能已经撤销的提交会话，仍要求精确归属。

## 锁顺序

不能只检查 `TokenBudgetLedger` 的内部 account→period 顺序。授权sqlGate可能先持resource_owner再调用预算逻辑，所以补偿采用 owner→account；直接ledger.reserve只锁account→period，不再取owner。补偿不修改period，也不在PG事务中等待模型或网络。所有删除同事务提交，后一步失败时前一步也回滚。

## 真实PG失败优先验证

测试 `RejectedRunBudgetCleanupPgTest` 使用独立 `lesson25_cleanup` 数据库和每测试独立tenant，结束只清测试夹具，未触碰真实双租户实验库。

- 重复五次，同一个服务端UUID依次预算/归属初始化→真实RunRegistry拒绝→补偿；账户和归属不增长，period仍保留。
- 错误subject或tenant不能删除；无预算账户旧路径可清。
- RESERVED、DISPATCHED、UNKNOWN、SETTLED、RELEASED五种call及单独非零计数、durable记录、零计数但有call均保留。
- 注入归属删除失败，验证账户与归属共同回滚。
- 并发真实reserve与补偿：只能留下完整预留及账本，或补偿先完成使reserve明确ACCOUNT_NOT_FOUND，不删除预留调用。
- 持owner事务时，PG确认补偿实际阻塞于owner锁，再调用真实reserve，验证锁序没有反转。

原实现真实运行6项，4失败、0错误、0跳过，包含重复拒绝后账户数量期望0实际1。修改后验证还包含预算账本/周期/工厂真实PG回归，日志在本地忽略目录 `target/lesson25-cleanup-*.log`。

2026-11-25 最终四类回归 `RejectedRunBudgetCleanupPgTest,TokenBudgetLedgerPgTest,TenantPeriodBudgetPgTest,ScopedModelBudgetFactoryPgTest` 共15项全部通过（失败0、错误0、跳过0，Maven成功），其中补偿专项7项。最终日志为 `target/lesson25-cleanup-final-green.log`。这是本地真实PG组件/并发证据，不宣称修复后的正式JAR已经完成重复HTTP拒绝验收。

此补偿不清既有孤立账户，不是全局历史保留/清理策略；进程崩溃后还可能需要有审计的离线核对。PG账本零预留不等于供应商在途工作或线程连接全归零，完整HTTP新版本仍需独立验收。
