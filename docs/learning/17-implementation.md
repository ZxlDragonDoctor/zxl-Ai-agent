# 第十七课实现与分层证据

更新：2026-11-06。**工程进行中，尚未完成本课整体验收。** 未自动修改个人掌握状态与笔记；当前新profile备份恢复已深比较，不能冒充恢复旧挂起profile。完整目标见[课程任务](17-tenant-authorization-and-tool-policy.md)与[实施设计](../superpowers/plans/2026-10-25-lesson17-authorization.md)。

11 月 5 日恢复工作后重新运行 16 类 83 项 Skill/发布相关测试，0 失败、错误、跳过，图谱 34 项及生产构建通过。此次未重跑真实 PG/模型或页面；10 月 30 日实际运行证据不改写成当天通过。精确复检范围见业务证据目录 `skill-recheck-2026-11-05.json`。正式 Skill 正向超时与图谱响应故障仍未解决。

## 1. 当前已实现的基础单元

### 2026-11-06：可信研究执行与私有任务事件

新增[可信上下文与私有队列](17-trusted-run-context.md)，研究工作Agent已接ScopedResearchFactory四参接口，同一Actor/run进入MCP和原文复核，取消准入接到实际订阅前。scoped任务/工具事件不再进入全局总线，任务完成先经过运行发布授权；固定本地队列禁止外部观察者回调异常，不改TaskLifecycle终态不变量。

11类90项自动化及2项真实PG测试通过，覆盖私有路由、两次模型记账、工具返回后撤权/取消、实际owner与membership撤销审计。PG用例的模型与工具仍为合成响应，没有新增真实模型/MCP业务链路。完整权限集合必须由正式运行入口固定，submit/状态/取消、编排报告隔离仍未接通。[实现导读、失败记录与精确范围](17-private-research.md)。

后续直接可信工作Agent补测实际qwen-plus、真实MCP与PG知识worker：乙首轮成功，甲首轮FAILED后同代码同30秒预算复验21078ms完成；两租户成功回答各自标记及引用，私有事件均绑定各自运行，保留甲首次失败且不宣称其根因已定位。[原始分层记录](17-private-research.md#后续真实研究链路补测)。这不等于正式编排HTTP或业务页面通过。

截图验收又发现2D相机一次性目标与持续布局的竞态，补持续fit意图和用户手势接管；图谱37项和最终构建通过。实际首次适配11节点无越界，平移/缩放/拖节点不被自动拉回，补SVG不选中文字。中英文、筛选、证据节点及恢复均记录在[15张截图与步骤](../images/lesson17-private-research-2026-11-06/README.md)。最终再次恢复完整备份，导出深比较相等。

### 2026-11-06：工作 Agent 的模型发送边界

正式工作 Agent 的模型步骤改用 DashScopeAgentStreamer，统一捕获同一 Entry 的权限、控制与模型租约，防止捕获后解绑导致模型记账脱离原运行。实际订阅在权限 gate 内执行短取消准入，远程等待在锁外；无可信上下文或无 streamer 均拒绝执行，不退回旧 ChatClient。ResearchAgent 在可信工厂及私有事件接通前明确拒绝 scoped 运行，仍未完成正式研究迁移。

新增专项9项及相关24项共33项通过；本机合成 HTTP 覆盖实际发送和拒绝时零请求，取消 gate 与解绑租约均有先失败证据。没有重跑真实 PG/付费模型或正式多 Agent 整体业务。[模型传输导读与证据](17-multi-agent-runtime-boundary.md)。

对应知识卡已同步中英文，实际操作打开阅读、滚动、语言切换、2D一跳、适配视口、关闭卡片并回到中文3D全图，保存[5张截图与逐步记录](../images/lesson17-model-transport-2026-11-06/README.md)。图谱34项测试及生产构建通过。开始前导出的完整备份最终恢复，再次导出解析深比较一致；未修改个人掌握与笔记。这仅验证本批知识展示，不替代正式多Agent业务验收。

### 2026-11-06：多 Agent 运行时身份键与捕获边界

修复共享执行器以模型 task.id 绑定控制的缺陷：改用内部对象身份键，模型 ID 相同或变化不再覆盖/丢失运行控制。每次模型调用捕获可信 scope/control，调用中解绑后仍按原 scope 拒绝已撤销的敏感结果。每个 RunRegistry.Entry 明确携带 OperationBoundary，旧注册重载仅保留课堂对照。6项专项及13项相关回归通过，精确范围和红灯见[运行时导读](17-multi-agent-runtime-boundary.md)。

该单元当时尚未接通实际发送；后续传输进度见上节，不能把供应函数前后check当成网络发送原子授权。正式submit/owner、状态/events/cancel、Orchestrator全局任务和observer隔离仍未接完，不据本批声明正式多Agent完成。新版知识卡双语、2D切换及导读路径实际核实，[截图记录](../images/lesson17-cli-runtime-2026-11-06/README.md)。截图滚动产生了阅读位置记录，最终再次恢复本轮原备份并导出深比较相等，个人掌握/笔记保留。

### 2026-11-06：可交付的离线账号初始化

增加 `ProvisionAccountCommand` 和 `tools/provision-account.ps1`，复用现有事务/哈希创建账号，密码通过隐藏控制台读取，不接收密码命令行参数；既有账号不会覆盖。参数先校验，成功/失败清除输入字符缓冲并关闭数据库，异常消息不直出。5项专项（含真实PG重复创建）与6项相关回归通过，脚本解析检查通过；人工控制台交互仍需独立验证。[使用与边界](17-account-provisioning.md)。不会因为交付命令而自动创建任何人的真实账号、升级角色或把旧会话绑定给新账号。

### 2026-11-06：有界池化与实际 Skill 正向运行

正式主应用/知识 worker/MCP worker 显式使用可关闭授权连接池，最多4连接、获取等待1000ms，无故障直连回退。37项相关测试通过，涵盖容量、归还隔离、同事务、撤销、关闭和回滚失败驱逐；[连接池导读](17-authority-connection-pool.md)保留红灯及对照量测边界。

新服务实际HTTP 8项通过，模型Skill结果为SUCCEEDED且有未创建草稿，四步总约22.35秒，未延长30秒预算，引用范围仍为甲。[结构化模型证据](evidence/17-real-skill-pooled-2026-11-06.json)。10月30日的超时仍是真实历史结果；一次正向成功不等于多轮稳定性或完整课程已完成。

图谱在新浏览器profile完成筛选、读卡、1/2跳、2D/3D、双语与全图操作，当前备份恢复后导出JSON相等；[6张截图与边界](../images/lesson17-graph-recheck-2026-11-06/README.md)。这不是原10月30日挂起profile，旧根因未确认；截图中保留当时身份服务未启动的REQUEST_FAILED，阅读卡遮挡也不证明所有节点可见。未覆盖全屏、重排、拖动与压力场景。

随后尝试补池化 Skill 页面时，IAB 返回不可用且浏览器库存为空；原生 Edge 操作又因无法可靠确认当前 URL 而停止。该次未读取凭据、登录或创建会话，没有新 Skill 截图；未用外部 Playwright/CDP 绕过。实际模型成功证据不替代这项页面缺口。

### 2026-11-05：真实 Skill 超时的排查证据

按请求帧数扩展本机受控模型 fixture，新增用例确认：64 个内容帧在内部 `onDelta` 不展示内容时，仍发生 66 次当前授权检查（64 帧、DONE、最终结果）和一次模型订阅启动。相关 4 类 **33 项通过，0 失败、错误、跳过**；日志 `target/lesson17-stream-investigation-20261105.log`，明细见 [测试范围](evidence/17-stream-profile-tests-2026-11-05.json)。这是现有行为的调查用例，没有削弱逐帧撤销保护或改写生产流程。

代码链为 `ActorBoundary.check → gateAll → SecurityDatabase.tx`：每次新建 JDBC 连接、开启授权事务并写实际审计。真实本机 PG 上，以合成维护者执行 20 次三动作 gate（agent.execute、knowledge.read、skill.execute），中位耗时 **141.5067ms**，总计 **2757.8602ms**，会话已撤销。[原始耗时](evidence/17-stream-profile-2026-11-05.json)不含凭据或原文。这不是包含会话/run 归属的五权限完整工作负载，更不是历史 30 秒超时的根因证明，不能简单线性外推整条链路。

独立审查同时确认：研究模型的选择与回答使用内部空增量消费者，仍逐帧检查；thinking 默认开启，也会产生内部帧。原文校验在三处不同边界重新读取，同次已按文档缓存，不能以重复查询为由删除一致性保护。同步 JDBC 位于模型流回调，是否阻塞共享网络线程还缺运行线程与并发证据。

下一步使用正式五权限与同主体并发流记录首帧、授权累计、连接/SQL/提交、原文复核及完整模型耗时；只记录计数与时间。任何连接复用或调度改造都须保留撤销、取消、真实发送/落盘与原文变更回归，不能靠扩大 30 秒预算把正向验收标绿。图谱页面卡住和个人备份恢复深比较仍未完成。

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

### 后续同批：SQL 与正式 Skill 的自动化进展

同连接事务已实现并迁移知识、记忆、检索、答案 SQL，相关 **8 类 65 项**通过。授权 Decision、审计和业务 SQL 共用事务，业务失败保存点回滚后延期抛错；数据库身份在启动核验，请求不再额外开核验连接。见 [事务导读](17-authorized-sql.md)。

正式 Skill 注册 v3/v4 并固定知识工具 v2 摘要；Provider 声明权限贯穿实际发布/保存，拒绝时不落敏感输出。先复现后修复中途超时/取消终态；**16 类 83 项**正式/旧 Skill、发布、会话与相关 Provider 回归通过。见 [正式 Skill 导读](17-formal-skills.md)。两组与此前范围重叠，不简单相加。

新代码已重启在 loopback 三端口。**7 项真实 HTTP 检查通过**：目录/版本、双空间资料、Skill 澄清无工具、跨空间会话 404、临时会话清理；两个独立 HTTP Cookie 容器不是浏览器截图。随后真实模型 Skill 已完成 MCP 并仅返回甲标记，但复核阶段耗尽 30 秒策略总预算，返回 `TIMED_OUT/SKILL_DEADLINE`、无草稿；全局 Agent 终态仍为 `EXECUTION_ERROR`。该成功分支验收**未通过**，只验证实际超时归并，不能由自动化绿灯推断完整业务已通过。首次辅助验收退出失败曾掩盖原错误，修正辅助程序后保留这次明确结果。证据见业务目录 `live-new-sql-http.json`、`real-skill-deadline.json`。

### 2026-10-29～30：正式 Provider 与真实业务阶段

正式目录只列出支持可信身份执行的 Provider。企业知识助手将 ActorRef、会话和运行归属传给 scoped gateway；研究助手将同一份 Java 权限范围传到模型、MCP 实际异步发送及原文复核。`runWithAuthorizedPublisher` 复用 BaseAgent 实际发布保护，避免授权事务嵌套等待。正式 MCP 管理接口要求 `knowledge.manage`，只读查询要求 `knowledge.read`；独立 MCP worker 不把权限失败或知识不可用伪装成成功空结果。

真实页面发现默认 Agent 仍选中未开放的类型，已用可用目录选择规则修复并补测试。页面使用同源 Cookie/CSRF 请求、身份范围会话存储键、维护者操作与真实存储状态；未发现工具、服务失败和成功结果分别显示。正式 Skill 与多 Agent 仍未迁移，不通过目录隐藏冒充完成。

实际启动 PG 15432、主应用 8123、知识 worker 18132、MCP worker 18135。甲、乙顺序登录，各自使用真实 HTTP 1024D Embedding 发布相同文档 ID、不同内容的合成资料。乙初始历史与知识均为 0；乙混合检索和重新打开原文只返回乙独立修订。甲知识问答同题首轮失败，重跑返回两条有原文支持的结论；首次失败根因尚未确认，新增阶段诊断不记录原文、异常消息或凭据。

甲真实研究调用出现 MCP 超时。随后测试复现共享连接被首次 5 秒发现预算永久缩短：后续 9 秒调用实际只得到约 4.4 秒服务预算。修复为固定 SDK 审核上限、每次初始化/调用仍使用各自截止；既不自动重试单次票据，也不延长短调用预算。修复后乙真实研究助手工具调用 2102ms 返回，生成设备重新登记与乙核验标记的结论，并附 C1 原文行号。一次成功不代表普遍稳定性，或证明首次问答失败与本问题同源。

2026-10-30 明确选择的 **9 类 42 项测试通过，0 失败、错误、跳过**；包含真实 PG、MCP SDK SSE、业务接线和安全诊断。前端身份 **10 项**、知识/工具相关 **50 项**通过，生产构建通过，保留既有 3D chunk 警告。日志分别为 `target/lesson17-business-green-20261030.log`、`target/lesson17-front-auth-20261030.log`、`target/lesson17-front-knowledge-20261030.log`、`target/lesson17-front-build-20261030.log`。与此前测试范围有重叠，不相加为全仓库通过。

阅读 [正式助手接线导读](17-scoped-agents.md)、[业务页面证据](../images/lesson17-business-2026-10-30/README.md)。本机内存不足导致一次主应用启动失败；仅对本轮自有验收进程设置有界堆后启动成功，未修改用户环境变量或关闭其他应用。当前只有一个可控浏览器 profile，顺序账号切换不是两个独立 profile 的并发验收。

**事务边界仍在改造验收**：此前部分 `KnowledgeDatabase.tx` 与 `OperationBoundary.start` 使用两个连接，部分检索/原文读取仅先 check。现有租户过滤和发布复核不等于每条 SQL 与 Decision 同事务；新连接传递与回滚方案仍须完整回归后另记证据。

共享 Cookie 的跨标签代际同步已补：消息只携随机发送端、序号和失效/刷新动作，不含身份或凭据。先清空旧状态再重取当前身份，拒绝重复/乱序并释放订阅。身份测试增至 **17 项**；两个实际共享 Cookie 标签完成「甲查询中从另一标签退出→旧标签匿名→另一标签登录乙→旧标签自动加载乙」验收。该证据不等于独立浏览器 profile。生产构建先发生本机原生内存错误，停止本轮自有 Java 服务后有界内存串行构建退出 0。

图谱更新后的筛选与文字已读到，但后续 DOM/CDP 和截图操作超时；恢复标签切到二维后取证也超时，本批没有新图谱截图，不标为全部视图通过。个人进度已先完整导出，未修改掌握/笔记；恢复及再次导出深比较待浏览器恢复后补做。详情见业务证据 README。

1. 企业知识/恋爱/研究/Skill Provider 已接线；须补正式 Skill 正向真实模型流程与页面、其他执行器及旧历史受控迁移。
2. 常驻进程已验收双工作空间知识；同事务 SQL 已有真实 PG 回归与新服务 HTTP 证据，仍需事实记忆页面隔离及完整撤销竞争业务验收。
3. MCP v2 调用级票据和共享连接已有分项证据；须接通全部业务执行器的模型排队/工具执行/保存/发布边界，并验证实时撤销。
4. 多 Agent 运行归属、旧入口和独立实验配置的完整封闭；不得仅隐藏前端按钮。
5. 登录界面与实际接口集成、两组独立浏览器身份、逐按钮/连续状态/语言与图谱截图。
6. 本课真实模型与故障验收、课程图谱和总路线最终同步、下一课完整任务。

基础测试没有覆盖上述整条业务链；本课暂不标为完成，也不声称已经接企业 SSO、MFA、生产沙箱或正式审批写入。

### 2026-11-07：正式多 Agent 入口与混合检索预算

`/multi-agent`正式模式现在使用可信会话创建运行：服务端UUID、同事务PG owner登记、私有事件/报告、本人状态列表、仅确认的取消回包。旧全局Controller只在legacy-demo注册；代理输入不能指定身份。任务开始和取消遵循短授权/控制准入，实际远程等待在事务外。阅读[归属登记](17-run-owner-registration.md)与[正式编排导读](17-formal-orchestration.md)。

正式入口15类111项测试通过，其中10项真实PG（业务角色为确定性替身）；10项实际HTTP检查分别覆盖本人读取、他人读/取消404、私有列表、全局入口关闭、身份注入400和CSRF403。前端24项、图谱37项与生产构建通过，保留既有3D大分包警告。这些范围不能累加成全仓库或真实模型保证。

真实页面首轮MCP超时，失败保留。另行复现混合检索让向量耗尽全部预算、关键词无法降级的问题：为整个向量路线分配包含排队的子截止，给关键词/最终PG检查留预算；全局超时、撤权和用户取消必须传播。六类26项通过，其中16项真实PG，详见[预算导读](17-hybrid-retrieval-budget.md)。未延长MCP协议上限，未切换内存库。

只重启知识worker后，原目标/原90秒预算真实复验成功：规划1次、研究2次实际模型调用；hybrid向量/关键词均OK、无降级，工具内部1452ms。最终只甲核验标记及C1/C2原文行号。取消后自动等待清理、中英文、本人历史选择/刷新、退出清空与换乙隔离均已截图。一个profile顺序切换不是两个独立profile并发验收；一次成功不能认定首次超时只有同一个原因。

图谱新增三个双语证据节点；实际筛选、关联回跳、3D阅读、2D一跳/二跳和双语通过，验收前备份经完整恢复、再次导出JSON深比较一致。见[逐状态截图与边界](../images/lesson17-orchestration-2026-11-07/README.md)。

本课仍进行中：旧历史受控导入、事实记忆与Skill等部分业务页面的完整撤权竞争验收、两个独立浏览器profile，以及整课最终回归仍需补齐。第十八课任务包保持已发布、工程未实施；第十课Temporal仍待独立补齐。

同批审查补充：复现授权回调返回成功、外层commit失败导致Future挂起，已增加[报告提交可见性屏障](17-run-publication-commit.md)。修复后最终16类120项入口回归通过（真实PG10项），日志`target/lesson17-formal-final-regression-20261107.log`，分层元数据见`evidence/17-formal-final-tests-2026-11-07.json`。这组包含并更新前述111项，不相加；commit失败由确定性边界注入，不是实际磁盘/网络故障。主应用重新启动后又通过同一组10项真实HTTP检查，见`evidence/17-orchestration-final-live-http-2026-11-07.json`。

最终版本真实模型复验出现新失败：向量路线6231ms失败，关键词取回甲原文、最终PG校验成功；工具6684ms返回明确降级，无内存切换。研究模型调用2次后EXECUTION_ERROR，无答案；当时没有足够服务端阶段诊断，根因未确认。截图19/20保留，因此不能把提交屏障修复前的正向结果称为最终版本整链通过。本课保持进行中。

随后新增[安全阶段诊断](17-research-failure-diagnostics.md)，12类72项通过（与120项有重叠，真实PG2项）。只记录固定阶段、白名单code和类型链；原异常、预算、权限和重试语义未改。重新启动主应用后同题/90秒做一次带诊断复验，最新运行3c4cdb23成功：模型选择query="VPN 401"、hybrid双通道918ms、无降级，仅甲来源、最终附原文引用。截图22～24保留；本次没有触发失败诊断，不能反推前次失败根因，也不是“日志修复模型”。modelCalls只统计门内尝试，失败时不等于实际HTTP/计费次数。整课剩余边界不变。

### 2026-11-07：旧会话受控归档

完成[归档导读及离线命令](17-legacy-history-archive.md)。正式模式下只接受显式离线清单，验证 UUID、SHA-256 与独立备份，在真实 PostgreSQL V2 事务内一并登记归属和收据。上线后读取原文件时重新校验；旧 `running` 保留为历史事实，页面显示「历史未结束」。续聊、删除、上下文预演及用旧消息创建事实记忆均被服务端拒绝。

最终 13 类 Java 回归 77 项通过（其中 24 项真实 PG，覆盖冲突回滚、并发重放、权限等），前端身份/只读与历史流程 24 项通过。实际接口在导入前通过 2 项检查，导入后通过 14 项检查；同一 PostgreSQL 16.15 实例重启后复验 12 项，重跑相同清单返回 `ALREADY_IMPORTED`，无内存替代。实际归档读取是历史展示，不调用模型或工具；真实模型证据仍见上批。

页面使用独立测试浏览器上下文，合成归档所属账号和另一租户同时打开，核对工具明细、只读控件、普通新会话、语言和退出清空。图谱新增双语阅读卡与课程关系；完整逐状态记录见[归档验收截图](../images/lesson17-legacy-archive-2026-11-07/README.md)。用户实际旧历史未被导入，个人学习数据未被测试写入。

第十七课仍进行中：后续补正式事实记忆、Skill 等业务的完整撤权竞争和相关页面证据；本批独立浏览器隔离只覆盖归档对话范围。第十八课[完整任务包](18-prompt-injection-and-runtime-isolation.md)仍待工程实施；第十课 Temporal 对照仍待补齐。

审查复现单份坏归档导致整个列表失败。详情仍拒绝未验证内容，列表仅跳过坏归档，普通会话继续可用。补跑 3 类 18 项通过（与此前 77 项有重叠），真实 PG 测试增加同租户另一主体也不能读取归档的断言。

### 2026-11-07：正式事实记忆与 Skill 撤权

正式事实记忆提议和更正改用当前会话已保存的真实消息 ID 和原文。历史刷新后出现可选来源，更正仅列出同类偏好的其他消息。服务端仍以当前主体和会话归属为准。两个合成账号实际完成提议、确认、召回、更正、换账号隔离和退出清空。

最终 7 类 Java 测试共 45 项，其中 21 项连接真实 PG。新增三项撤权竞争覆盖记忆来源已核验但尚未写入、Skill 研究仍在等待、Skill 草稿已准备但尚未进入发布队列。记忆没有新增行，Skill 的迟到建议和草稿没有发布，真实 PG 记录了拒绝。Skill 自动化使用确定性研究替身。

实际页面用真实配置运行 Skill v3。工具发现刷新后可用，研究走到核验与建议阶段后达到截止，未发布草稿。当前结果保留为失败样本，早前实际模型成功证据另列。阅读[记忆与撤权导读](17-memory-and-skill-revocation.md)和[逐状态验收](../images/lesson17-revocation-2026-11-07/README.md)。
