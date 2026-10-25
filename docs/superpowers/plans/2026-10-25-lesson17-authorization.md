# 第十七课：身份、权限与租户隔离实施设计

> **状态：待实施。** 本文为只读源码盘点后的设计，不是验收记录；没有运行 Maven、浏览器或真实依赖测试。实施时按职责使用子 Agent，先失败用例再实现，逐批验收、选择性提交和推送。

**目标：** 在现有统一页面上完成真实可撤销认证、双租户 PG 数据隔离、MCP/Skill 调用级授权和撤销时序审计；封住所有旁路，保留个人历史与学习进度。

**架构：** 8123 为唯一浏览器业务入口；18132 与 18135 只接收经验证的内部调用授权。PG 保存认证/成员/资源归属/授权决策，Java 显式传播身份引用；固定业务配置快照与实时授权决策分开。正式配置默认拒绝未分类入口，独立旧实验配置继续标明其非多租户边界。

**技术栈：** Java 17、现有 Spring Boot 3.4.6 / Spring AI M6、现有 MCP SDK 0.7.0 legacy SSE、JDBC/Flyway、真实 PostgreSQL + pgvector、Vue 统一对话页面。不开框架大版本升级，不引入另一套业务调度器。

依据：[第十七课完整任务包](../../learning/17-tenant-authorization-and-tool-policy.md)、`skills/SkillExecutionPolicy.java`、`skills/SkillRunner.java` 与本次盘点的 Controller、窄启动类和数据库迁移。以下源码路径相对仓库根目录；拟新增文件用“新增”标注。

## 一、已核实的入口与缺口

### 1. 主应用 8123，业务 context-path `/api`

下表路径均在 `/api` 之后；正式 Filter 必须以 servletPath/pathInfo 的实际值测试，不能误把 context-path 当业务前缀漏拦。

| 路径及方法 | 现状与风险 | 本课处理 |
| --- | --- | --- |
| `GET /agent-types` | 全局 Provider 清单，无主体过滤 | 登录后按实际可执行 Provider/动作过滤；不能仅隐藏前端选项 |
| `POST/GET /conversations`；`GET/DELETE /conversations/{id}` | 文件会话无 tenant/owner 字段，UUID 不是授权 | PG 归属索引 + 服务层每次授权；列表先按归属选 ID 再加载文件 |
| `POST /ai/agent/chat`、`/ai/manus/chat` | 请求带会话 ID 和 Skill 选项，异步运行不带可信 actor | 显式 ActorRef 进入运行、Provider、模型与工具边界，保存 run 归属 |
| 同上两个 `GET` 路径 | GET 创建运行并消费模型 | 正式配置关闭，前端使用带 CSRF 的 POST 流式请求 |
| `GET /ai/love_app/chat/sync`、`/sse`、`/sse2/`、`/sse/emitter` | 旧 LoveApp 可直接用 chatId 读写共享记忆，绕过统一会话 | 正式配置关闭旧入口；Love Provider 走统一会话。独立旧实验显式开启 |
| `POST /ai/learning/context/preview` | 接收上下文预览，可能读取指定会话 | 会话归属授权后读取；预览不授予模型或工具额外权限 |
| `GET /ai/learning/skills`、`/{id}/{version}` | 只读清单；Java 本地发布/回滚，HTTP administrationEnabled=false | 按当前可执行范围过滤；版本不可用与身份无权分开记录；不新增匿名发布接口 |
| `GET /ai/learning/mcp/status`；`POST /refresh`、`/lookup` | 任意调用者可探测/刷新唯一 MCP 连接和检索 demo tenant | 查询需 knowledge.read；连接刷新需受限运维权限且不能踢其他租户上下文；脱敏状态 |
| `POST /ai/multiagent/orchestrate`、`/runs`、`/orchestrate/stream` | 请求 context 由用户控制，可创建全局调度运行 | context 只保留业务字段，另附可信身份；run 归属先保存再调度 |
| `GET /ai/multiagent/runs/{id}`；`POST /{id}/cancel` | 只按执行 ID 查状态/取消 | 先校验同租户且属于当前用户；不可见 ID 统一 404 |
| `GET /ai/multiagent/orchestrate/stream` | GET 触发运行 | 正式关闭，使用受 CSRF 保护的 POST |
| `GET /ai/multiagent/status`、`/agents`、`/events` | 共享 observer/bus 可能暴露其他租户目标、状态、消息 | 用户只看自己运行的汇总/事件；全局运维视图默认关闭，不能用 tenant-admin 读全站数据 |
| `POST /ai/multiagent/agents/{id}/message` | 用户可直接向共享 Agent 发消息，绕过 run 身份 | 正式关闭直接注入；有业务需要必须绑定授权 run 和明确消息类型 |
| `GET /ai/multiagent/harness/{id}`、`/harness/benchmark` | GET 可真实调模型/工具，读取共享 Agent | 正式配置关闭，保留显式隔离的开发测试入口 |
| `/image` 的 RequestMapping；`GET /image/multipleConditions` | 任意 HTTP 方法或 GET 消费模型，异常可能回内部消息 | 本课正式配置关闭旧示例；后续如开放，独立 image.generate 动作 + POST + 预算 |
| `POST/GET /ai/learning/tickets`，profile `lesson8-demo` | 单用户内存工单，无租户 | 正式配置禁止同时启用；保留独立实验，非正式写入工具 |
| `POST /ai/learning/persistent-runs`；`GET /{id}`；`POST /{id}/cancel`、`/recover`，profile `lesson9-demo` | H2 六表无 tenant，恢复扫描全局 | 正式配置禁止旧实验入口；第十九课正式工单写入另建租户权威表；不能宣称本课已迁移第九课工作流 |
| 文档/Swagger/OpenAPI、错误转发、OPTIONS、静态资源 | 应用配置存在 Swagger；未见认证链；CorsConfig 对全部 Origin 开 credentials | 正式配置文档关闭或运维专属，OPTIONS 只回确切允许 Origin，不触发业务；统一错误不泄露凭据 |

本次 `rg` 未见已有 Spring Security/OAuth/JWT 实现。正式配置应明确列出允许的公开路径（登录/登录所需 CSRF 引导、最小存活探针），其余默认拒绝或认证后按动作授权。新增 Controller 未分类必须被测试发现。

### 2. 知识 worker 18132，无 `/api` 前缀

`Lesson12Demo` 用窄 MVC 容器显式注册 Bean，不扫描主应用安全配置；**只改主应用的 Filter 不会保护它**。当前 Vite 将 `/api/ai/learning/knowledge` 去掉 `/api` 后直接代理到此端口。

| 路径 | 现状 | 必须增加的边界 |
| --- | --- | --- |
| `GET /ai/learning/knowledge/status` | 返回固定 tenant、DB/embedding 信息 | 用户脱敏可用性；内部详细状态需要运维动作 |
| `GET /documents`、`/documents/{id}`、`/search` | KnowledgeService 构造固定 demo-enterprise | 从可信调用上下文创建不可变范围服务；原文与列表和检索同样授权 |
| `POST /documents`、`DELETE /documents/{id}` | 固定租户写入和删除 | knowledge.manage；Embedding 前与索引发布前重新检查 |
| `POST /retrieval` | 固定租户关键词/向量/混合/重排 | knowledge.read；候选获取前 SQL 过滤，重排/模型前再次核范围 |
| `GET /evaluation/status`；`POST /evaluation/seed`、`/run` | 独立评估服务会建数据并消费模型 | 正式用户入口关闭；仅隔离评估身份/数据范围，不能让租户管理员读取全局评估内容 |
| `POST /answers` | body 可选 profile，默认 alice；固定 tenant | 移除 profile 身份选择，可信 subject 传给检索/记忆/引用；会话 ID 验归属 |
| `GET /memories`、`/recall`；`POST /proposals`、`/{id}/confirm`、`/{id}/reject`；`PATCH/DELETE /{id}` | profile 可选 alice 等教学身份 | subject 来自认证上下文；命令只能操作本人记忆，source session/message 必须同属主体 |

新正式窄启动与旧 demo 配置分开，但复用仓储/算法。正式进程必须缺授权不启动/不处理，不能缺配置时自动选择 demo。需要在窄容器显式注册内部请求认证、错误处理与异步清理。

### 3. MCP 18135，无 `/api` 前缀

- `GET /sse`：当前只检查 Origin（浏览器请求拒绝），允许本机任意无 Origin 客户端占有唯一 SSE。
- `POST /mcp/message?sessionId=...`：当前校验连接 ID、输入体大小；连接 ID 不等于认证。
- JSON-RPC initialize/ping/tools/list/tools/call：单一 server 与工具；`knowledge_lookup_v1` 只含 query/topK/mode/budgetMs，后端固定 demo-enterprise。
- SingleClientSseTransport 已解决连接代际、内部 RPC ID 和迟到响应归属；不应重写这些已验证机制来做业务认证。

必须在打开 SSE 与 POST 入口校验服务端签发的服务凭据，**每次 tools/call 再绑定真实 actor 与 action**。共享的一个应用连接可以保留，不能在全局可变字段上保存“最近一次租户”。初始化/发现不授予业务权限；缺少调用授权时原 v1 不能作为旁路执行。

### 4. 数据存储缺口

| 存储 | 已有隔离 | 缺口/迁移决策 |
| --- | --- | --- |
| lesson12.knowledge_document/revision/chunk | 主键/外键与查询已有 tenant 条件，chunk 还绑定 profile | 租户来自固定构造参数；保持结构和现有数据，从可信 Scope 创建服务，无需重建全部向量 |
| lesson12.confirmed_memory | tenant+user 条件、版本、TTL、部分唯一索引 | user 来自 profile；历史表只按 memory_id 关联，读历史时必须先授权 parent，不能直查历史 |
| runtime-data/conversations/*.json | UUID、消息/run 完整性与版本 | 无 tenant/owner；新增 PG 归属索引，不在用户未授权时读取文件正文 |
| 运行/observer/messageBus | JVM 运行注册表、控制和事件 | 缺可信 owner 和逐事件 scope；共享 Agent 状态不能向普通用户原样公开 |
| 第九课 H2 agent_run/task/attempt/business_operation/ticket/run_event | 业务幂等、租约和 fencing | 无 tenant；正式第十七课中不可开放旧 profile，隔离实验不等于完成正式租户写入 |
| 缓存与摘要/事实上下文 | 部分模型 profile 和请求内缓存 | 盘点所有持久或共享缓存键。无部署项记录无缓存，不为了课程新增缓存 |

## 二、最小且完整的实施选择

### 1. 身份与权威数据

新增 `security/` 包：`AuthenticatedActor`（不可变主体）、`ActorRef`（异步身份引用）、`AuthenticationService`、`AuthorizationService`、`AuthorizationDecision`、`ServiceCallAuthorizer`、`SecurityDatabase`、`SecurityConfiguration`/窄进程 Filter。

拟新增 `src/main/resources/db/lesson17/V1__identity_and_authorization.sql`，独立 `lesson17` schema 与迁移历史，不改已发布 lesson12 V1/V2：

- `tenant(tenant_id,status)`；`subject(subject_id,login_name,password_hash,status)`。
- `membership(tenant_id,subject_id,role,status,version)`，复合主键；本课角色固定 employee / knowledge-maintainer，角色不能授予任意全站读权限。
- `auth_session(session_id,credential_hash,subject_id,tenant_id,expires_at,revoked_at,version,csrf_hash)`，credential_hash 唯一，明文令牌不落库/日志。
- `authorization_epoch(tenant_id,subject_id,version)`，成员/角色/动作撤销事务必须锁并递增该行；会话撤销同事务更新会话版本。
- `resource_owner(resource_type,resource_id,tenant_id,subject_id,state,version,legacy_import_id)`，当前会话与 run 的归属先写入该表；实体查找同时匹配 type+id+tenant+owner。
- `authorization_audit(decision_id,run_id,phase,tenant_id,subject_id,session_id,action,resource_type,resource_id,version,allowed,code,created_at)`，不可含原文、口令、token；sessionId 仅内部审计展示时脱敏。
- `service_call_ticket(ticket_hash,audience,action,actor_session_id,resource_ref,request_sha256,expires_at,consumed_at)`，只保存摘要；过期票据清理有界；内部请求单次使用。

本课不要求把 JSON 全部搬到 PG，但 PG 归属必须是访问前置权威：文件存在却无归属的旧会话默认隔离，**绝不自动绑定首个登录者**。以受控离线导入将用户明确保留的旧演示历史绑定到专门 legacy 身份；先备份、校验 ID/摘要，记录导入清单。甲乙教学账号只看各自新合成数据。最终项目仍应提供可登录查看旧历史的受控入口，不以隐藏旧历史声称已保留体验。

登录建议采用成熟 PasswordEncoder + PG opaque session；依赖版本由当前 Boot BOM 管理并通过实际依赖树核实，不预写未核实版本。主应用采用统一认证链和 Cookie+CSRF；窄 worker/MCP 显式装配内部认证。CORS 改为明确开发 Origin，正式同源；不能保留 wildcard credentials。

### 2. 浏览器、worker 与 MCP 的传播

```text
浏览器同源 Cookie + CSRF
 → 8123 验证 PG auth_session/member → ActorRef
 → 授权 + 归属检查 → 主应用 KnowledgeGateway
 → 单次内部 ticket（audience、action、request hash、session、短期限）
 → 18132 验票并查当前权限 → scoped Knowledge/Retrieval/Memory/Answer services

Provider / Skill / Research / DAG 固定 ActorRef
 → model schema 只含业务参数
 → 调用入口再次决策 → 注入基础设施 ticket
 → 18135 在工具回调前验证并剥离 ticket → 按 actor 创建 scoped retrieval
 → 结果回原 RPC 所有者 → 应用验证来源/权限 → 发布
```

主应用新增固定路径/方法的 `KnowledgeGatewayController`，覆盖目前正式知识 API；禁止通用任意 URL 代理。Vite 的 knowledge 代理改到 8123；Cookie 只对主应用可用，绝不把用户 session 原文传给 worker。worker 仅接受内部票据，GET 原文也要授权；拒绝浏览器 Cookie、伪造 tenant Header 和缺服务上下文的直连。

MCP SDK 0.7.0 的每调用上下文能力实施前先核实本地实际 API。可使用**基础设施专用的调用信封**（服务端审核的 v2 契约）承载 opaque ticket，由 Java 在模型参数严格校验后注入；不把 ticket/身份字段提供给模型，也不容许模型自带保留字段。若采用此方案，wire schema 与 model schema 分开建模，固定受审查摘要/工具版本，旧 v1 在正式配置拒绝；不能悄悄改同一工具 schema 还沿用旧摘要。每次实际 HTTP/RPC 尝试发新票据；已消费/过期/错误受众/参数摘要不符直接拒绝，不无限重试。

票据只把既有权限委托到一次调用，不是独立长期权限快照；消费时仍查当前会话和授权代际。单个共享应用 SSE 继续由原传输层绑定结果。不要选“每个用户一个 MCP 连接”绕开问题，当前服务只允许一条连接，会相互替换。

### 3. `Decision` 必须与实际判断同一同步边界

第十六课 `Snapshot.policyVersion` 仅是**选择 Skill 时**的版本。`authorize()` 后再调用 `version()` 会被并发 revoke/restore 插入，记录一个从未参与该次判断的版本；这是第十七课应补齐的审计不变量。

统一不可变结果建议为：

```java
record Decision(String decisionId, String version, boolean allowed, String code) {}
enum Phase { ENTRY, DEQUEUE, DISCOVERY, TOOL_DISPATCH, MODEL_DISPATCH,
             INDEX_PUBLISH, EVIDENCE_READ, RESULT_PERSIST, RESULT_PUBLISH, HISTORY_READ }
```

- JVM 内 classroom policy：同一个 `synchronized` 块内读取 revision、评估所有条件并创建 Decision；revoke/restore 使用同一锁。旧的 `authorize()` 可以委托 `decide()` 并根据结果抛异常，审计调用者要保留这份 Decision。
- 正式 PG：同一事务内锁定 epoch/session/member，读取 version 并做判断，插入 phase audit，返回 Decision。失效、服务不可用也要形成稳定失败分类；DB 完全不可用时不能伪造一个“当前 PG version”。
- 禁止让 Snapshot 起始版本冒充后续 `TOOL_DISPATCH`、`MODEL_DISPATCH`、`RESULT_PUBLISH` 的版本。事件使用每次 Decision 的 version 与 decisionId；选择时版本仍保留历史意义，不重写旧快照。
- `policyVersion` 应明确是具体权威组合（如 epoch+sessionVersion+serverPolicyRevision），不要把多个版本拼成不具有明确含义的自由文本。注册表内容摘要单独记录。

**撤销线性化边界：** 同一主体的授权 gate 与 revoke 锁相同权威行。对 SQL 读写，授权与业务 SQL 在同一受限事务；对发起外部动作，用短事务锁内检查并完成“启动异步 HTTP 请求/提交受控调用”的边界，随后立即释放，不能持锁等待30秒远程完成。对结果持久化/公开事件发布，检查与实际提交/入公开发送队列必须在同一 gate 内，队列消费者发送前还重查。不能仅做 `decide(); // ... ; send()` 两个相互独立动作后声称瞬时撤销。

每个 gate 都有预算与取消，失败关闭；明确“撤销提交后开始的动作被拒绝”，已经启动的外部动作/已发出的字节不可召回。实际 transport 如果无法把发送边界准确放进 gate，先收紧为完整结果缓冲后经 gate 发布，并如实写出在途发送边界，不能虚构强一致保证。

## 三、按依赖顺序实施与验收

每阶段先写失败用例并留红色结果，再实现、重跑本阶段和相关回归。下面命令是**待执行**示例，不代表已运行；实际测试类名由对应实现提交固定。Maven 在共享 target 下串行执行。

### 阶段 A：身份与原子 Decision（先完成，其他阶段依赖它）

- [ ] 增加 PG identity schema 与配置失败关闭；核实依赖，不更改个人 application-local。
- [ ] 先写 `AuthenticationPgTest`：正确/错误登录、过期、伪造 Cookie、撤销、CSRF、重启；口令/token 不在响应与日志。
- [ ] 写 `AuthorizationDecisionTest`：并发 revoke/restore 与 decide 时 `(version,allowed,code)` 必须对应同一次锁内判断，phase 逐次记录；不同 subject 不污染。
- [ ] 最小实现当前主体、退出、明确受限的撤销入口；普通用户不能为其他主体改角色。
- [ ] 运行 `./mvnw.cmd -Dtest=AuthenticationPgTest,AuthorizationDecisionTest test`，核对真实 PG 未 skip。分迁移/契约与认证/Decision 两笔提交。

### 阶段 B：入口收口与资源归属（依赖 A）

- [ ] 建 `EndpointAuthorizationTest` 的参数化路径/方法表，逐项断言匿名、甲、乙、受限维护者；未知路径默认拒绝，旧 profile 不能与正式模式共存。
- [ ] 会话创建先写 owner，读取/删除/运行前查归属；原文件与 PG 注册不跨库原子，失败时留不可见孤儿可修复，不能让无owner文件默认公开。
- [ ] 为 multiagent run/observer/bus 增 owner；普通用户查不到全局事件，受控禁用旧消息注入/harness/GET执行与 image 示例入口。
- [ ] 测旧历史隔离与显式导入，保留原文件摘要；补同租户不同用户案例与 ThreadLocal 线程池复用案例。
- [ ] 运行定向会话/工作流/入口测试及已有会话相关回归；按归属仓储、API策略、旧入口隔离分别提交。

### 阶段 C：可信 worker 与双租户 PG（依赖 A/B）

- [ ] 增内部 ticket/受众/请求摘要校验和固定 KnowledgeGateway；调整 Vite 正式路径去8123。
- [ ] 真实 PG 插入甲乙同 documentId 不同秘密标记；构造每请求不可变 Knowledge/Retrieval/Answer/Memory scope，去掉正式 profile 参数身份选择。
- [ ] 给 list/detail/import/delete/search/retrieval/answer/memory/来源回查逐项用例；覆盖 Embedding 进行中撤销后不得发布索引、失效旧构建不得复活资料。
- [ ] 直连18132缺票据/错action/重复票据/参数篡改拒绝；业务服务直接调用也不能绕过权限。DB断开不得内存回退。
- [ ] 真实 PG migration/双连接并发/进程重启分别记录。按信任传播、知识范围、记忆/回答范围提交。

### 阶段 D：MCP / Skill / 模型与撤销时序（依赖 A/B/C）

- [ ] 给 MCP SSE/POST 加服务身份，tools/call 加受审查的逐调用授权通道；保留连接代际回归，验证普通本机进程无凭据不能占连接。
- [ ] ActorRef 显式经过 AgentConversationService、Provider、SkillRunner、ResearchRunner、DAG，禁止在单例 Gateway 或 ThreadLocal 残留“当前租户”。
- [ ] 在 discover/dispatch/出模型队列/证据回源/结果持久化/发布做 phase Decision；TOOLS 完整结果也是敏感事件，不能只挡最终草稿。
- [ ] `RevocationBoundaryPgTest` 用闩锁覆盖队列等待、工具返回、模型排队、发布之前撤销；记录下游调用计数为0和对应 decision version。重授权不会让已终态旧run自动复活。
- [ ] 甲乙同时相同查询/模型选择/共享MCP连接，结果、引用、审计始终归原主体；调用票据不得进入模型prompt、ToolResult、公开SSE或截图。
- [ ] 跑现有MCP/Skill/Research与相关PG回归，再真实模型正反例。按MCP可信协议、执行授权、撤销与审计提交。

### 阶段 E：统一双语页面与完整证据（依赖前四阶段）

- [ ] `/chat-room` 增登录、服务端主体/租户、退出与受限权限管理；不同账号使用独立浏览器会话验两租户。
- [ ] 所有请求统一401/403/404/503处理、CSRF与身份代际；切账号清旧来源/流/历史内存，迟到HTTP/SSE不得覆盖新主体。
- [ ] 先前端失败用例，再实现；跑构建及图谱校验。逐按钮实际操作、连点/切历史/切语言/移动布局截图，保留失败与修复重跑。
- [ ] 真实模型记录明确模型/知识/Skill/政策版本，人工核对引用与工具实际调用，不把模型口头拒绝等同权限通过。
- [ ] 同步17实现记录、18完整任务包、路线/图谱关系与source evidence；保留个人进度，导入恢复后比较导出内容。
- [ ] 按前端身份体验、课程/图谱、截图证据细分提交；push并核对远端，不提交凭据、数据库/会话运行数据或本地环境。

## 四、结课判定与依赖边界

以上 A→B→C→D→E 必须连通，不能只交一个登录页或认证 Filter。正式模式两租户共享真实 PG，跨所有开放路径隔离，MCP 远端拒绝伪造主体，Skill 记录每个 phase 的实际 Decision 版本，撤销后不再启动受保护动作或发布敏感结果，才满足本课目标。

旧 H2/内存工单实验、未受控文件/终端工具、全局调试/评估入口在正式模式关闭，是明确的产品范围；它们仍需保留独立学习入口与边界说明。第十八课做隔离防护，第十九课建设正式授权工单写入；不能把关闭旧示例描述成已经实现它们的生产安全版本。正式模式如果仍可经任一路径无票据读取18132、无调用身份执行18135，或用全局demo身份调用知识，则第十七课未完成。

未接企业 SSO/MFA、未做生产密钥轮换与部署网关时继续标明。性能与容量只报告本次真实环境结果；授权gate的锁等待、PG故障和在途动作残余边界单独记录。本设计没有执行上述实现、测试或验收。
