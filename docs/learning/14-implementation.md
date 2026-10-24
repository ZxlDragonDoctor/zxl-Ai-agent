# 第十四课实现与验收：引用、拒答与长期记忆

更新日期：2026-10-25。状态：**教学工程与功能验收已完成；原始真实语义评判的预设留出门槛未通过，单独保留质量边界。** 本记录保留失败与修复边界，未据此声明生产可用。任务目标见[完整六单元任务包](14-citations-abstention-and-memory.md)，下一课见[第十五课完整任务](15-mcp-tool-contracts-and-adapters.md)。工程状态、验收结果与个人掌握分别记录。

## 1. 本课解决什么

检索只是候选；引用需要能解析到本次允许的有效版本，结论还需要被原文支持。跨会话记忆只保存用户明确确认的受限事实，模型推测和历史回答不能自动变成事实。

```text
固定演示身份 → 真实PG当前证据 → 请求内证据编号 → 结构化结论
  → 引用/支持性/当前版本检查 → 回答、澄清、拒答或服务失败
用户原话 → 待确认偏好 → 显式确认 → PG保存
  → 更正后重新确认 / TTL到期 / 删除 → 后续召回过滤
```

本批复用第十二、十三课的PG文档与检索，以及原统一会话Provider机制。确认事实使用结构化PG记录，未增加事实向量索引；旧JSON聊天历史、`HierarchicalMemory`和本课事实记忆分别存在，不能混称同一种能力。

## 2. 实际源码与页面入口

源码根目录为 `src/main/java/com/zxl/zxlaiagent/`：

| 文件 | 实现职责与边界 |
| --- | --- |
| `knowledge/answer/AnswerService.java` | 请求内snapshotId/E1…En、来源校验、规则与支持性检查、回答状态、版本和记忆复检 |
| `knowledge/answer/AnswerModel.java` | 生成/评判端口及确定性演示替身；替身用于控制流，不证明真实语义 |
| `knowledge/answer/HttpAnswerModel.java` | 显式HTTPS生成和评判、严格JSON、有限输入输出和剩余预算；不升级Spring AI依赖 |
| `knowledge/answer/AnswerController.java` | 隔离教学profile中的回答接口 |
| `knowledge/memory/MemoryService.java` | PG候选、确认、拒绝、更正、TTL、删除与受限召回 |
| `knowledge/memory/MemoryAnswerContext.java` | 查询可信演示用户事实，以独立字符预算提供给回答服务 |
| `knowledge/memory/MemoryController.java` | 注册演示用户的管理与召回接口，区分参数、冲突、不可见和存储失败 |
| `conversation/agent/KnowledgeAssistantProvider.java` | 在原统一会话注册`enterprise-knowledge`，调用本机知识worker，发出真实`KNOWLEDGE_RESULT`事件 |

统一页面为 `/chat-room`，选择“企业知识助手”；未另建知识聊天页。Provider固定Alice身份，记忆面板可在受限Alice/Bob间切换做隔离实验；面板切到Bob并不会把统一对话变成Bob身份。独立worker沿用本机18132、`lesson12-demo`，不使用主应用的`/api`前缀。

### 实际接口

| 方法 | 路径 | 请求要点 |
| --- | --- | --- |
| POST | `/ai/learning/knowledge/answers` | question、conversationId、mode（demo/real）、budgetMs（100–30000）、可选profile（alice/bob） |
| GET | `/ai/learning/knowledge/memories` | profile，返回可管理记录 |
| GET | `/ai/learning/knowledge/memories/recall` | profile、maxChars，仅召回已确认且未失效事实 |
| POST | `/ai/learning/knowledge/memories/proposals` | profile、sessionId、messageId、userText、ttlSeconds |
| POST | `/ai/learning/knowledge/memories/{id}/confirm` | profile、version |
| POST | `/ai/learning/knowledge/memories/{id}/reject` | profile、version |
| PATCH | `/ai/learning/knowledge/memories/{id}` | profile、version、来源ID、userText、ttlSeconds；更正后重新待确认 |
| DELETE | `/ai/learning/knowledge/memories/{id}` | profile、version |

回答的`SERVICE_FAILURE`使用503，其余业务状态200，非法请求400。记忆错误区分400输入、404不可见、409版本/状态冲突、503数据库不可用；不回退内存后报告成功。

真实模式配置为显式 `LESSON14_CHAT_ENDPOINT`、`LESSON14_CHAT_API_KEY`、`LESSON14_CHAT_MODEL`、`LESSON14_JUDGE_MODEL`、`LESSON14_CHAT_REVISION`；不在文档提交密钥。Provider通过 `LESSON14_ANSWER_GATEWAY` 指向受限loopback worker，`LESSON14_CONVERSATION_MODE`选择demo或real；默认demo不能当成真实模型验收。

## 3. 引用、支持性与保守回答策略

### 引用与有效版本

服务器签发snapshotId和请求内证据编号，携带documentId/revisionId/chunkId、原文UTF-16范围与行号、实际送入模型的文本、摘要、截断标记和文档generation。源文本每块最多1200、合计4000个Java字符，截断区间与展示保持一致。

从检索结果组装快照时校验原文；外部生成/评判真正出队前及发布前复查当前有效版本。复查包括generation，防止“删掉再导入完全相同文本”使旧revision重新出现后被误认为同一有效状态（ABA）。模型不能自行声明可信路径、URL或旧请求E1为当前来源。

### 结论与状态

规则校验结论长度/数量、引用存在及重复、数值和保守否定。真实模式再逐条调用支持性评判，使用SUPPORTED/CONTRADICTED/INSUFFICIENT；支持性失败或格式/服务错误不能包装成已经证实。无界修复未实现：不支持的结论移除，有剩余则部分回答，全部移除则无依据拒答。

状态区分ANSWERED、PARTIAL、NEEDS_CLARIFICATION、INSUFFICIENT_EVIDENCE、SERVICE_FAILURE。VPN/连接类问题缺少三位错误码且未明确通用排查时，确定规则要求补充信息；这不是通用意图识别器。关键词/语义召回命中本身不够证明回答了问题。

预算覆盖检索、排队、生成、逐结论评判和最终检查。模型工作池有界（2工作线程、8排队），超时/取消停止等待并拒绝迟到发布；远端实际中止能力仍取决于传输与服务，不能把取消当作远端回滚。

### 真实语义评判：保留失败结果

固定集`support-helpdesk-v1`为12个人工标注案例，分开发/留出，覆盖原文支持、数值、否定、半句支持、条件遗漏、多证据、无关、未执行动作、冲突与资料内指令等。真实生成器与评判器均用`qwen-plus`，通过不同调用与提示词承担不同职责；同模型评判不构成独立权威。

| 实际运行 | 正确分类 | 不支持结论被误判SUPPORTED | 预设留出门槛 |
| --- | --- | --- | --- |
| 主验收原始运行（prompt-v1） | 10/12 | **1**（留出注入案例） | **false，未通过** |
| 额外重复运行（同12题，同版本） | 10/12 | 0 | 报告为true，但不能替换主验收失败 |

实际共发生**24次真实judge调用**，不把重跑当成新增12题独立样本，不合并成24题质量分数，也不选择较好结果宣称通过。主运行另一个分类差异为条件遗漏：人工标签INSUFFICIENT，模型给CONTRADICTED；应保留标签与理由，不为迎合输出而改标注。

针对已见注入失败，v2增加**保守来源指令隔离**：发现显式“忽略先前/系统规则”等覆盖指令时，候选被隔离，不把它作为正常业务原文直接发送。已知攻击样例已加入PG回归。这个规则会遗漏变形攻击，也可能误伤合法文本；它是对已知问题的防护，**不是全新留出集通过，不消除主运行失败，不证明通用提示注入安全**。后续需版本化新的独立案例，再按预设门槛评估。

## 4. 事实确认、隔离与生命周期

只接受明确原话白名单：中文/英文、简洁/详细、列表/段落偏好；模型不做任意敏感事实提取。原话先产生PENDING，未确认不召回；确认后才是CONFIRMED，更正后立即退出召回并重新PENDING。重复确认按版本和状态幂等，冲突明确409。

事实保存tenant/user、来源session/message、提取版本、状态/version和创建/过期/删除时间。演示TTL为5秒至30天，查询即时过滤到期记录，不依赖后台清理准时执行。删除属于业务不可见与软删除，不声称历史会话和所有备份物理擦除。

召回默认最多5条、1000字符；不会把所有历史拼进模型。模型任务真正出队时重新读取事实，若排队期间被删除/到期/更正，不发送旧快照；返回前再次复检，失败响应丢弃旧记忆。该检查解决本应用的排队窗口，不声称已发送给远端的数据可以被撤回。

事实用户范围来自注册演示profile，仅限Alice/Bob，不能接受任意tenant或让偏好授予权限。底层服务测试覆盖可信Scope范围，但生产认证、角色策略与登录在第十七课，不能把本机切换profile称为真实身份授权。

## 5. 自动测试、数据库与实际重启

截至本记录当前确认的本课专项为**48项通过**：

| 分组 | 项数 | 证据含义 |
| --- | --- | --- |
| `MemoryPgTest` | 13 | 真实PG迁移/事务、确认/隔离、更正竞争、TTL和删除等 |
| `MemoryApiPgTest` | 4 | 真实PG管理接口、严格输入及时间序列化等 |
| `AnswerPgTest` | 18 | 实际PG证据快照、删除/改版、跨域、排队记忆变化、generation及已知注入防护等 |
| `AnswerPolicyTest` | 3 | 固定规则与保守政策 |
| `AnswerApiTest` | 3 | 请求校验和状态/服务失败区分 |
| `HttpAnswerModelTest` | 4 | 实际本地HTTP协议替身，严格schema/预算/异常；非真实语义模型 |
| `KnowledgeAssistantProviderTest` | 3 | 统一Provider注册、worker契约和失败传播 |

最终跨第十一至十三课等33套186项回归退出0，失败/错误/跳过均0，48项包含在186项中不重复累计。完整集评测曾在默认60秒预算下诚实返回partial并触发失败；完整集正确性用例现显式使用既有120秒预算，原1秒partial断言保留，生产默认未修改。真实模型调用单列，不混入这48项。

真实PG主线沿用PostgreSQL16.15+pgvector0.8.6，未不可用自动切内存。记忆是PG结构化表，并非pgvector事实索引。迁移、事务/竞争、删除/TTL及真实检索用例分别取证；这不等于生产负载压测。

**实际应用进程重启已取得证据：** 知识worker的PID由25904变为45452，重启前后Alice已确认英文偏好仍为`en`、version4，比较结果equal=true、召回1条。截图`18-process-restart-recall.jpg`记录重启后实际页面召回；这不是仅new一个Service对象。它证明应用进程跨重启读取真实PG的该事实，不能扩大成故障转移/灾备或PG数据库进程重启测试。

## 6. 页面发现的真实问题与修复

记忆页面验收发现：后端Instant以数字秒返回，前端按毫秒解释，界面落到1970并把有效候选误判过期。保留`06-date-contract-bug.jpg`，没有删除失败截图。先补真实API失败用例，再由后端`@JsonFormat`输出ISO日期，前端增加格式防守，修复后截图`07-iso-pending-fixed.jpg`复查正常PENDING与可操作状态。

已逐步实际操作并截图：选择企业知识助手、新会话、打开事实面板、提交候选/未确认不召回、拒绝、重新提议、确认/再次召回、Bob隔离、更正打开/取消、提交更正后退出召回、再次确认英文、刷新、实际worker重启后召回。

当前资料目录为 `docs/images/lesson14-acceptance-2026-10-24/`，连续编号保留操作顺序；目录日期沿用本批起始日，部分验收跨至2026-10-25。截图19为真实回答进行中，**进行中不能当作最终回答成功**。真实完整回答、来源阅读、拒答/故障、删除/TTL、图谱/语言等最终结果见[逐操作截图索引](../images/lesson14-acceptance-2026-10-24/README.md)。44记录外部真实失败，45重试恢复；失败响应不伪装为无资料。

前端19项知识库/事件与31项图谱共50项通过，最终生产构建退出0；101节点141关系28课校验通过。筛选、关系、阅读卡、中英文与实际390宽移动布局有独立截图，不以构建代替页面操作。个人学习进度和笔记保持独立。

## 7. 完成边界与后续质量工作

已经实现受限证据回答、确认事实PG生命周期及统一Provider接入，专项与真实worker重启证据已取得；**主真实语义评判门槛未通过，不能将第十四课写成无条件质量验收通过**。

最终回归、构建、真实完整回答与截图已归档。针对v2防护的新独立留出集仍属后续质量工作；既有12题重跑、已知攻击回归、下面5个开发案例和规则替身不能替代它，不能宣称语义质量无条件通过。当前没有生产认证/ACL、通用事实抽取、事实向量索引、完整防注入沙箱、Neo4j或GraphRAG部署。

## 8. 下一课：工具契约与MCP适配

[第十五课完整任务包](15-mcp-tool-contracts-and-adapters.md)已发布，六单元为：企业查询工具契约、可复现MCP服务与客户端生命周期、协议/业务错误适配、预算/取消/幂等、身份与研究Agent接入、分层测试与逐按钮验收。

已有ToolResult和StructuredMcpToolCallback先核实复用；ResearchAgent当前只分析上下文资料，第十五课才把受控远程只读检索真正接入。任务发布不代表工程实现，个人掌握不自动更新。

## 9. 真实完整回答与复合问题

[五问原始报告](../images/lesson14-acceptance-2026-10-24/pipeline-v2.json)使用真实PG、text-embedding-v3 1024维、qwen-plus生成prompt-v2和同模型独立调用的judge prompt-v1；部署标签acceptance-20261024并非不可变权重承诺。4次生成、3次评判。两道可答题返回ANSWERED、无错误码返回NEEDS_CLARIFICATION、年假资料缺失返回INSUFFICIENT_EVIDENCE、VPN端口+年假复合题返回PARTIAL并列出年假缺口。

本次3条结论逐项与原文核对：可解析引用3/3、支持3/3、需引用结论覆盖3/3；应拒答组错误回答0/1，可答组不必要拒答0/2。澄清和部分回答分别单列，不能充入可答/应拒答分母。分母为0的其他组应报未定义。这是小型合成开发验收，不是新独立留出集或生产质量证明。

生成器新增missingInformation，校验最多5项/单项300字符/总1000、去重与格式；模型自报缺口仍可能漏掉子问题。偏好映射明示语言、详略和排版，但保守否定规则可能误拒跨语言改写，不能保证所有风格要求完整生效。源覆盖指令仅保守识别已知模式。

页面还发现切换真实模式后旧demo预览未清空，42记录修复前、43确认修复后清空。预览问题变化也清空结果，会话改变则重建面板，防止旧请求窗口串位。最终默认问题401对应仓库原VPN示例；本批809资料完成验收后已软删，原资料未覆盖。

复现专项：先在本地安全注入LESSON12_DB_URL/USER/PASSWORD，再运行`./scripts/lesson14/test.ps1`；该入口仅真实PG与本地HTTP替身，不调用付费模型。真实模型需另显式配置LESSON14_CHAT_ENDPOINT/API_KEY/MODEL/JUDGE_MODEL/REVISION；统一会话模式为LESSON14_CONVERSATION_MODE=demo或real。启动沿用Lesson12Demo及第十二课脚本；Provider通过本地18132网关接入，默认固定Alice。
