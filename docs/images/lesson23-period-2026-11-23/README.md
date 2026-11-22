# 第23课租户周期预算验收（2026-11-23）

真实PostgreSQL 16.15、V9迁移及29项定向回归通过，失败/错误/跳过均0；明细见[test-results.json](test-results.json)。37项图谱回归及前端生产构建通过，仍有既有大分包提示。不是全仓回归或生产压力测试。

新增3项综合PG测试覆盖跨run并发争额、UTC跨日绑定、UNKNOWN保留、释放重放、实际超额、周期调用次数、运行拒绝不扣周期、租户隔离及独立数据库连接重建。数据库进程重启、最终版本真实供应商HTTP结算和跨日生产调度本批尚未验收；不能沿用11月20日模型用量记录证明新周期机制通过。

失败记录：旧账户已使用但未绑定周期时原实现未拒绝，行为断言失败后增加保护；最终回归前一轮有一次PG建连SSL探测超时。pg_isready确认正常后，在本机显式sslmode=disable串行复跑9类29项通过，不代表TLS验证。

复跑：设置LESSON12_DB_URL为本机测试PG JDBC地址、LESSON12_DB_USER和LESSON12_DB_PASSWORD，使用mvn test指定TenantPeriodBudgetPgTest,TokenBudgetLedgerPgTest,ScopedModelBudgetFactoryPgTest,StreamBudgetPgTest,StreamBudgetPoolPgTest,RoutedStreamBudgetPgTest,BoundedModelRouterTest,StreamingResearchBudgetWiringTest,FormalGatewayActivationTest。最终复查使用相同已编译源码的surefire:test；本机日志保留target/lesson23-period-final-recheck.log。

页面5212代理18301；主服务在既有验收副本中仅定向更新usage包及V9。不是干净检出的部署验收。尚未启动本批Worker/MCP，不声称完整业务链已恢复。

页面操作：第24课打开阅读卡，确认完整任务文档路径，切英文、2D再3D，卡片保留，截图01、02、03。第23课中文历史用量卡→英文→2D→3D→HTTP历史记录，截图01-live至07-http。历史数字21/1869保持原日期含义，底部明确新周期机制另记；没有把历史模型调用伪装成本批新测试。个人掌握与笔记未修改。

[周期预算知识](../../learning/23-tenant-period-budget.md) · [第24课完整任务](../../learning/24-reproducible-deployment.md)

只读独立审查未发现确定阻断。边界：按run首次初始化UTC日归集，旧run跨日仍消耗旧周期；默认预算开关关闭；其他模型入口、UNKNOWN运维对账、持久路由审计与实时预算事件尚未补齐，第23课仍进行中。
