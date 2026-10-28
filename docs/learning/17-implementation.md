# 第十七课实现与分层证据

更新：2026-10-27。**工程进行中，尚未完成本课整体验收。** 个人学习进度、笔记和掌握状态不变。完整目标见[课程任务](17-tenant-authorization-and-tool-policy.md)与[实施设计](../superpowers/plans/2026-10-25-lesson17-authorization.md)。

## 1. 当前已实现的基础单元

### 数据与身份契约

独立 `lesson17` PostgreSQL schema 保存主体、租户成员、会话、资源归属、授权审计与调用票据；使用独立 Flyway 历史，保留原 `lesson12` 知识与向量。缺少 PG 配置或权威数据库不可用时拒绝认证，没有内存替代路径。

`ActorRef` 是异步运行携带的不可变身份引用；`AuthenticatedActor` 是重新解析出的当前有效主体。调用者提供的 JSON 或 Header 不能直接生成可信主体。`Phase` 和 `AuthorizationDecision` 将“在哪个阶段、按哪个实际版本允许或拒绝”记录到审计。

依赖只增加 Boot BOM 管理的 `spring-security-crypto`，实际依赖树为 **6.4.6**；没有引入自动生成登录页的 Security starter。密码使用 BCrypt，凭据和 CSRF 使用随机值、数据库只存摘要。离线 `provision()` 是受控管理原语，没有公开注册或任意授权接口。

### 会话与撤销

`AuthenticationService` 实现密码与成员校验、8 小时不透明会话、到期、退出撤销、CSRF 校验与轮换。每个 **subject + tenant 最多 5 个有效会话**；登录淘汰不会撤销其他租户会话。密码按 UTF-8 限制 72 字节，避免 BCrypt 静默截断；未知账号也执行 dummy hash 校验。

认证测试包含独立 JVM 从 PG 恢复会话，令牌经子进程标准输入传入，不出现在命令行和测试日志。此证据证明凭据状态跨 JVM 保留，不代表整个应用的重启验收已完成。

### 实时决策与调用票据

`AuthorizationService` 对固定动作、当前成员/会话、资源归属生成原子 Decision。`gate()` 在短事务中检查并启动受控动作，与撤销共用 epoch 锁；远程请求必须只把启动动作放入 gate，不能持锁等待整个模型调用。

`ServiceCallAuthorizer` 签发最长 60 秒、默认 30 秒的单次票据，绑定受众、动作、主体会话、资源及请求摘要。消费再次检查当前权限；**消费票据后，实际动作仍必须进入 gate**。该基础类的测试不表示 worker/MCP 已经接入它。

## 2. 已验证的数据库并发问题

| 问题 | 失败证据 | 修复与覆盖 |
| --- | --- | --- |
| 登录先锁旧 session、撤销先锁 epoch，形成反向锁序 | 真实双连接交错出现登录 `AUTHORITY_UNAVAILABLE` | 登录先锁 epoch，再按 subject + tenant 淘汰；满会话与撤销交错回归 |
| 事务等待锁期间已到期，`now()` 仍是事务开始时间 | resolve、Decision、票据消费三项错误放行 | 先取得 epoch/session/ticket 权威锁，再用独立 SQL 的 `clock_timestamp()` 判断当前到期 |
| 跨租户同资源引用不应授予访问 | 独立同资源引用拒绝用例 | tenant + subject 归属判定；上限另按租户隔离验证 |

并发测试使用 `pg_blocking_pids` 确认另一连接实际阻塞后才跨过期限，避免只靠随机延时推断锁等待。修复前定向 5 项出现 3 失败、1 错误、0 跳过；修复后通过。独立只读复核确认原触发边界已关闭。

## 3. HTTP 契约实现进度

已编写登录/状态/退出 Controller、严格 JSON 解析、HttpOnly + SameSite Cookie、同源/CSRF Filter 和默认拒绝的入口目录。正式会话 Controller 已调用带 ActorRef 的服务；完整 Agent/worker/MCP 业务链仍待接通。

- 匿名状态提供登录 CSRF 引导；已登录状态不每次轮换 CSRF，避免其他标签页失效。
- 登录只接受 `loginName/password/tenantId`，拒绝重复字段、未知身份字段、非字符串、尾随第二个 JSON 值及超过 4096 字节的请求。
- 已登录身份必须先退出再切换；退出先校验 CSRF、撤销权威会话，再清除 Cookie。
- 公开主体仅返回 subject、tenant、loginName、role；不返回内部 sessionId、凭据或摘要。
- 生产 HTTPS Cookie 应为 Secure；当前 Cookie 随实际 HTTPS 请求设置 Secure，开发 HTTP 与正式部署配置仍待集成验收。

## 4. 本轮实际测试

2026-10-27：真实 PostgreSQL **16.15**，以下 Maven 定向命令退出 0：

```powershell
mvn -q '-Dtest=AuthenticationPgTest,AuthorizationDecisionTest,SecurityConcurrencyPgTest,EndpointPolicyTest,WebSecurityFilterTest,AuthControllerTest' test
```

| 类 | 执行数 | 范围 |
| --- | ---: | --- |
| AuthenticationPgTest | 6 | 真实 PG 密码、摘要、过期、撤销、CSRF、独立 JVM |
| AuthorizationDecisionTest | 13 | 实时决策、审计、归属、票据；包含继承认证回归 |
| SecurityConcurrencyPgTest | 12 | 实际锁等待、到期、锁序、租户上限与归属；包含继承认证回归 |
| EndpointPolicyTest | 4 | context-path、路径分类、关闭旧入口、动作映射 |
| WebSecurityFilterTest | 8 | Mock Servlet，匿名、CSRF、Origin、不可见资源、显式请求上下文 |
| AuthControllerTest | 10 | Mock 身份服务，Cookie、公开主体、严格 JSON、退出与账号切换 |
| 合计 | **53** | **0 失败、0 错误、0 跳过；执行数不等于独立业务场景数** |

本地日志：`target/lesson17-auth-http-final.log`；并发失败及修复日志为 `target/lesson17-concurrency-red.log`、`target/lesson17-concurrency-green.log`。静默日志需与对应 Surefire XML 汇总共同核对；运行日志不作为源码提交。

随后增加 `AuthHttpPgTest` **4 项真实 PG + MockMvc** 集成用例，连同 Controller/Filter/入口目录共 **26 项通过、0 跳过**，日志 `target/lesson17-mvc-pg.log`。覆盖匿名引导 → 登录 → 状态 → 错误 CSRF → 退出 → 旧凭据拒绝；外来租户候选拒绝后正常重试；非法 JSON 的安全 MVC 错误；跨站与未分类路径关闭。MVC 异常由 `WebSecurityAdvice` 统一输出安全 code/message。该测试使用实际数据库和 Servlet/MVC 链，但没有打开真实 HTTP 端口，不能替代浏览器截图。

### 会话服务的显式身份入口

`ConversationService` 已增加带 `ActorRef` 的创建、列表、正文、详情、恢复和删除入口。创建先登记 PG owner 再写指定 UUID 文件；列表先查 owner ID，只加载本人文件；无 owner 的旧文件保持隔离，不自动归属首位登录者。文件失败留下的 owner 占位不会被列表捏造成会话。

`AgentConversationService` 的 scoped chat/preview 显式传身份，异步回调捕获引用，保存结果时重新授权。Provider 新入口默认拒绝，不能回退旧创建方法。撤销后拒绝保存回答并释放运行 lease；下次受权读取可将遗留 running 标为 interrupted，不能在撤销后偷偷用 legacy 写入。具体 Provider、模型、逐事件发布与 Agent 聊天 Controller 的正式接线仍待后续完成。

`FormalConversationController` 现已在 required 模式接通创建/列表/详情/删除，身份只取自 `AuthenticatedRequest`。旧 Controller 仅在 legacy-demo 或未指定模式时装配；二者互斥。正文拒绝伪造身份字段、重复或尾随 JSON、非字符串及超大请求；入口与服务层不可见资源都统一 404。新增 `FormalConversationControllerTest` 12 项，与相关回归 26 项通过，日志 `target/lesson17-conversation-http-green.log`；红日志记录旧匿名入口实际返回 200、预期 401。

### 正式配置与实际 HTTP 阶段

`FormalSecurityConfiguration` 以 `agent.security.mode=required` 装配 PG 权威、浏览器 Filter 和可信 Handler 清单。迁移期间未指定模式仍保留独立 legacy-demo 行为，不能将其作为正式租户授权。未知模式拼写直接阻止启动；正式模式禁用旧 wildcard CORS，只接受同源或显式完整 Origin。第八/九课旧实验 profile 不得混用，检查包括 Spring 实际生效的 default profile；普通 active profile 会正确覆盖未生效的 default。

`TrustedHandlerInterceptor` 要求实际 Controller 类型也在已迁移清单中，防止已分类 URL 转入仍调用 legacy 服务的旧方法。当前只激活身份、最小存活和正式会话 Controller，其他已分类但未迁移业务返回 `AUTHORIZATION_INTEGRATION_PENDING/503`，未知或关闭路径返回 404；这不是完成 Agent 授权的替代方案。

独立复核发现 default profile 漏检；先通过 `target/lesson17-default-profile-red.log` 复现，再按 Spring `acceptsProfiles` 的有效配置语义修复。最终 **13 类 97 项通过，0 失败、0 错误、0 跳过**，日志 `target/lesson17-formal-stage-final.log`，精确汇总见阶段证据 `backend-formal-tests.json`。包括正式配置 7 项、可信 Handler 4 项及正式会话 HTTP 12 项；此前 74 项保留为早期阶段记录，不能与 97 累加。

真实主应用已于本轮用 required 模式启动，监听 loopback 8123，PG 16.15；`/health/live` 200，匿名 `/auth/status` 200、HttpOnly CSRF，匿名会话 401。**12 步实际 HTTP 检查通过**：甲登录/创建/读取，乙用独立 Cookie 容器登录后访问甲会话 404，旧 GET 模型执行 404，员工管理知识 403，未迁移 Agent 目录 503，双方退出与删除本轮合成会话。证据见 `live-http-scope.json`。这两个 HTTP 容器不是两个独立浏览器 profile；没有因此声称模型、worker 或 MCP 已验收。原 3 个演示会话文件仍保留，没有自动绑定测试主体。

### 前端身份模块阶段

`sessionRequest.ts` 管理同源 Cookie/CSRF、身份代际与迟到响应检查；`api/auth.ts` 负责状态、登录和退出；`AuthGate.vue` 提供身份栏与双语登录组件，公开课程始终可读。前端 `test:auth` **10 项**、`test:knowledge` **40 项**由主任务重跑通过，生产构建通过，保留既有 3D 大 chunk 警告。

当前工作树 App/API 接线已用于实际浏览器验证：匿名刷新、错误密码进行/拒绝态、正确登录、未迁移业务拒绝、退出及匿名图谱。全局 App、其余业务请求与代理切换仍随正式业务链继续集成，不能把单独身份组件提交视作统一业务前端已交付。源码已有的前端入口和 API 改动将在完整链路核实后另按职责提交。

新增 `ScopedConversationServiceTest` **9 项**和 `ScopedConversationPgTest` **4 项**（真实 PG + 临时 JSON 文件），验证双租户、同租户不同主体、旧文件、撤销及文件读取前的归属检查。先复现无权访问 busy 会话泄露 409，再将归属检查置于 lease 检查之前。红/绿日志为 `target/lesson17-conversation-behavior-red.log`、`target/lesson17-conversation-green.log`、`target/lesson17-conversation-pg-green.log`。

最终将上述认证/授权/HTTP、会话新增测试及 `ConversationContextIntegrationTest` 一起运行，**10 类共 74 项通过，0 失败、0 错误、0 跳过**，日志 `target/lesson17-stage-ab-regression.log`。该数字是本轮明确选择的回归范围，不能外推为全仓库测试全部通过。

### 知识图谱阶段检查

图谱同步为 **116 节点、164 关系、28 课**，第十七课 `in-progress`、第十八课 `planned`。34 项图谱测试和前端生产构建通过；本轮实际操作课程筛选、十七课阅读卡、1/2 跳、3D→2D、英文、源码证据与十八课先修入口，随后增加真实登录、拒绝与退出，保留 **17 张窄视口截图**。个人数据先导出、恢复后再导出，真实服务重跑后再次导出，JSON 深比较相等。

截图与限制见[阶段证据](../images/lesson17-acceptance-2026-10-27/README.md)。最初旧主应用的 `NOT_FOUND` 失败已保留；随后启动正式主应用，实际完成单身份登录、错误密码重试和退出，公开课程正常。**没有将全部按钮、两个独立浏览器身份或真实 Agent 业务标为验收通过**；桌面尺寸和完整链路仍待后续集中验收。

### 2026-10-28：执行与知识服务阶段

新增组合权限 `gateAll`、显式 `OperationBoundary/ActorBoundary`，同一 PG 权威事务检查 Java 选择的权限列表，拒绝及启动失败仍保留实际审计。模型实际订阅、内容增量及最终结果重新授权；无帧等待也每 50ms 响应取消。`prepareStream` 等 MVC handler 初始化后才启动工作线程，避免早期缓存帧绕过之后的发布检查。准备后请求失败会放弃并释放 lease；正式旧 scoped chat 入口显式报错，正式 Controller 必须使用 prepared hook。

`ScopedKnowledgeFactory` 已实现按可信 tenant/subject 的知识与记忆范围、实际用户消息来源核对和回答的组合权限。V3 迁移在真实 PG 扩展身份列，保留旧数据。独立正式 worker 要求服务 Bearer 与绑定 method/query/body 摘要的单次票据，严格拒绝课堂 profile；发布缓冲限 1MiB，刷新 writer，实际 socket I/O 位于授权锁外。MCP v2 将模型 schema 与授权票据分开，并在共享 SSE 连接上保持每调用身份。

本轮明确选择的 **18 类 92 项整合测试通过，0 失败、错误、跳过**；另运行 **13 类 100 项旧行为回归通过**，两组范围有重叠，不累加为独立场景。真实 PG、独立 JVM worker、实际 MCP SDK SSE、本机受控模型 HTTP 和 MockMvc 是不同层证据。日志 `target/lesson17-integration-stage.log`、`target/lesson17-legacy-regression.log`；精确 XML 汇总与 11 张图谱操作截图见 [执行阶段证据](../images/lesson17-execution-2026-10-28/README.md)。页面核实双语、1/2 跳、3D→2D、新导读路径，恢复备份后导出 JSON 深比较相等。

阅读 [执行边界导读](17-execution-boundaries.md)、[可信知识范围导读](17-scoped-knowledge.md) 和 MCP 代码目录 `FORMAL-V2.md`。尚未重新启动正式主应用来验收新链路；Controller、gateway 和 Research 的整条业务接线仍在进行，不据阶段测试宣布完成。已经开始的网络发送无法撤回，也不声称 PG 与 TCP 原子。

## 5. 尚未完成的本课范围

1. 继续将 scoped 执行接到全部具体 Provider；prepared 聊天 Controller 已接线和单独验证，尚需正式运行验收，并执行旧历史受控迁移。
2. 内部网关、正式 worker 与 scoped 知识服务已有实现和分项测试；须在常驻进程中验证双租户知识、记忆与原文隔离的完整业务链。
3. MCP v2 调用级票据和共享连接已有分项证据；须接通全部业务执行器的模型排队/工具执行/保存/发布边界，并验证实时撤销。
4. 多 Agent 运行归属、旧入口和独立实验配置的完整封闭；不得仅隐藏前端按钮。
5. 登录界面与实际接口集成、两组独立浏览器身份、逐按钮/连续状态/语言与图谱截图。
6. 本课真实模型与故障验收、课程图谱和总路线最终同步、下一课完整任务。

基础测试没有覆盖上述整条业务链；本课暂不标为完成，也不声称已经接企业 SSO、MFA、生产沙箱或正式审批写入。
