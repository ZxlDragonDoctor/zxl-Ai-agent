# 第十二课实现与验收：真实 PostgreSQL 知识接入

更新日期：2026-10-24。状态：**独立教学链路已实现，真实 PG、模型、数据库重启与页面截图已验收。** 工程完成不自动改变个人掌握状态。

## 1. 先理解本课解决的问题

文档不再仅在应用启动时被放进内存。现在同一份资料具有文档 ID、不可变原文版本、稳定分块 ID、向量模型身份和生效指针。先在数据库登记构建意图，在事务外计算向量，最后在短事务中完整写入并发布。只有当前生效且未删除的资料能被查询。

```text
接收受限 Markdown/text 正文
→ 原文校验与确定性身份
→ PG 短事务登记版本号
→ 事务外 Embedding
→ PG 条件发布：版本、分块、向量与 active 指针
→ 精确余弦距离查询，仅当前生效版本
```

正式存储为本机独立安装的 **PostgreSQL 16.15 + pgvector 0.8.6**，本次端口 **15432**；本次没有使用 Docker。`SimpleVectorStore` 的旧恋爱资料路径保留作对照，没有替代本课 PG 测试。教学 schema 为 `lesson12`，Flyway 管理 V1 迁移。

## 2. 实际代码职责

以下路径相对 `src/main/java/com/zxl/zxlaiagent/knowledge/`：

| 文件 | 实际职责 |
| --- | --- |
| `KnowledgeText.java` | 校验 ID/输入大小和 Unicode；保留原 Markdown；按标题层级与字符上限切块；记录 UTF-16 半开区间及行号；忽略围栏代码内假标题 |
| `KnowledgeEmbedding.java` | 模型端口、维度/有限值/非零校验和 PG vector 字面量编码 |
| `DemoKnowledgeEmbedding.java` | 确定性 8 维散列替身，只验证生命周期，不证明语义相关性 |
| `HttpKnowledgeEmbedding.java` | 显式 HTTPS 模型服务、连接/请求超时、禁重定向、校验响应及维度；没有失败后自动退回 demo |
| `KnowledgeDatabase.java` | 仅接受 PostgreSQL URL，Flyway 与显式事务，SQL/锁等待限制 |
| `KnowledgeService.java` | 清单、详情、去重、构建预留、版本 fencing、原子发布、失败状态、删除和精确检索 |
| `KnowledgeController.java` | 固定演示身份的实验 API、结构化输入/冲突/失败响应 |
| `Lesson12Demo.java` | 隔离 `lesson12-demo` 入口，绑定本机回环地址；不初始化原主应用 RAG/MCP/付费模型 |

SQL：`src/main/resources/db/lesson12/V1__knowledge_pgvector.sql`。三个表分别保存文档清单、不可变版本快照、可检索块与向量。复合主外键包含 tenant；向量列支持不同维度，但每条记录受 `vector_dims` 约束，检索以模型身份与维度过滤，不混合排名。没有建立 ANN 索引，当前是第十三课的精确检索基线。

## 3. 身份、来源和更新规则

- 文档 ID 由请求明确提交，受安全字符和长度限制；sourceName 只接受文件名，不读取服务器任意路径或 URL。
- 原文保存于版本表，中文、emoji、CRLF 与独立 CR 的坐标按原始 Java UTF-16 字符串计算；正文不做破坏性清理。
- revision 摘要包含租户、文档、原始正文及切块/模型 profile。当前切块上限为 400 个 UTF-16 单位，按标题边界并尽量在换行处断开，避免切开 surrogate pair。
- 记录 normalizedHash，但当前采用 rawHash 参与 revision，**仅 CRLF 改成 LF 也会建立新版本并重新计算向量**。这是原文定位优先的明确取舍，不声称实现规范化等价文本的向量缓存。
- 完全相同正文/profile 且已生效时返回 `UNCHANGED`，不调用 Embedding。mtime 不参与身份，本 API 也不接收文件时间。
- 目前没有跨版本的块向量缓存、增量局部复用或断点续算；正文变化时重建此文档的新版本。
- sourceName 是展示名，不是权限依据；本课不提供自动识别文件重命名或目录扫描。

## 4. 并发、故障和删除

1. 预留事务在文档行锁下增加版本；外部 Embedding 在事务外完成，避免持锁等待网络。
2. 发布事务再次锁定文档并核对版本号。删除或更新已增加版本时，旧构建返回冲突，不得复活已删资料或覆盖较新发布。
3. 分块写入、active 指针切换及旧向量清理处于同一数据库事务；SQL 中途失败全部回滚。
4. Embedding/发布失败单独记错误状态，有旧生效版本时继续可用。并发较新版本已经接管后，旧失败回报不能覆盖新状态。
5. 删除清空 active 指针并在同一 PG 事务物理删除分块；原文版本保留为退役审计记录。本课没有外部向量库异步清理队列。
6. 同步导入进程中断可能留下 BUILDING 记录；需显式重新导入，不声称已有后台自动恢复所有未完成索引作业。

固定 tenant 为 `demo-enterprise`，这是演示隔离范围，**不是登录认证或生产 ACL**。API 的 SQL 使用参数绑定；失败响应不回显服务商正文、数据库连接或凭据。完整授权、配额、审计和生产入口加固在后续课程落实。

## 5. 运行与页面入口

独立入口类为 `com.zxl.zxlaiagent.knowledge.Lesson12Demo`，默认端口 18132。需要显式注入 `LESSON12_DB_URL`、`LESSON12_DB_USER`、`LESSON12_DB_PASSWORD`；URL 必须连接真实 PostgreSQL。不要把密码写进提交或截图。

- 默认 `LESSON12_EMBEDDING_MODE=demo`：仍使用真实 PG，只将向量计算替换为可控散列。
- `LESSON12_EMBEDDING_MODE=http`：需 `LESSON12_EMBEDDING_ENDPOINT`、`LESSON12_EMBEDDING_API_KEY`、`LESSON12_EMBEDDING_MODEL`、显式 `LESSON12_EMBEDDING_REVISION` 和维度。缺少必需项即失败，不自动退回内存或 demo。
- 向量适配采用独立 JDBC + HTTP 端口，保留 Java 17 与 Spring AI M6/M6.1，不依赖未核对的最新版 PgVectorStore API，也未升级原有 Spring AI 依赖。

| API（独立服务，不带 `/api`） | 行为 |
| --- | --- |
| `GET /ai/learning/knowledge/status` | PG/扩展实际版本、模型身份、维度、演示 tenant |
| `GET /ai/learning/knowledge/documents` | 文档清单与生效状态 |
| `POST /ai/learning/knowledge/documents` | `{documentId,sourceName,content}` 同步导入 |
| `GET /ai/learning/knowledge/documents/{id}` | 原文、生效版本及分块坐标 |
| `DELETE /ai/learning/knowledge/documents/{id}` | 原子删除生效资料与分块 |
| `GET /ai/learning/knowledge/search?q=...&limit=5` | 当前模型空间的精确余弦候选，limit 1..50 |

前端业务入口 `/knowledge`，Vite 将 `/api/ai/learning/knowledge` 代理至本机 18132 并移除 `/api`。这是开发代理，不代表生产反向代理或主应用业务 RAG 已接入。Agent 类型仍使用统一 `/chat-room`，本页是资料管理工具，不是新增一个独立 Agent 对话页。

## 6. 已取得的验证证据

以下运行结果由本批主 Agent 实测报告；不将文档静态阅读算作执行测试。

| 项目 | 已取得证据 | 不能据此推断 |
| --- | --- | --- |
| 本课自动测试 | **22 项通过**：8 项 PG 仓储、2 项真实 PG + MockMvc API、7 项原文/切块、2 项本地 HTTP 契约、2 项故障响应、1 项 profile 隔离 | 22 项并非全部是数据库测试；HTTP 契约服务不是付费模型 |
| 相关后端回归 | 本课与原相关测试合计 **106 项，退出码 0** | 不等于整个项目所有历史/外部依赖测试通过 |
| 实际 PG 环境 | 本机 PostgreSQL 16.15、pgvector 0.8.6、端口 15432，执行 Flyway 与真实读写 | 不是 Docker/Testcontainers、高可用或生产负载验收 |
| 真实 Embedding | DashScope `text-embedding-v3`，1024 维，独立探针 **2 次调用**（页面验收另有真实请求）；同内容第二次导入 `UNCHANGED`；查询命中 1 条、distance=0 | 同文本距离为 0 只能证明写入/查询一致性，不证明中文召回质量或误召回率 |
| 跨应用进程持久性 | PG 测试由不同 JVM 写入/读取原文、版本与向量，并核对一致 | 这不是数据库进程自身重启 |

测试源位于 `src/test/java/com/zxl/zxlaiagent/knowledge/`。覆盖：重复导入不额外嵌入、原文回读、更新不暴露旧块、删除不可召回、租户隔离、错误维度、模型空间隔离、Embedding 故障、SQL 注入故障点原子回滚、慢构建与删除竞争、旧构建晚于新构建提交、跨 JVM 读取。审查发现的主应用扫描隔离和标题层级问题已补测试与修复。

复现本课专项（预先注入真实测试库环境变量）：

```powershell
mvn '-Dtest=KnowledgeTextTest,HttpKnowledgeEmbeddingTest,KnowledgeProfileTest,KnowledgePgTest,KnowledgeApiPgTest' test
```

缺少真实 PG 环境时 PG 测试应明确失败/阻塞，不以 H2、内存容器或 skipped 测试顶替。

## 7. 本批页面与重启验收

- 真实 PostgreSQL 进程从 PID 36376 停止后以 PID 45708 启动，同一数据目录；`vpn-guide` 版本 2、修订 `2575dcee3085dea36f72a33a41098f2263c74c57d02e4a9d10ea1c4e3ba37b96`、4 块和原文保留。独立知识服务也已重新启动。
- `/knowledge` 实际点击刷新、示例、导入、重复导入、详情/原文/分块、编辑更新、Top K、检索来源、删除取消/Escape/确认、语言切换；390px 无横向溢出。保留 VPN 示例，删除仅针对新建合成验收资料。
- 停库实际返回 503 STORAGE_UNAVAILABLE，恢复后可再次读取。不回退内存。修正旧误导提示与搜索条件变化后残留结果。
- `/learning` 原 87 节点与新增 3 节点均逐个点击、核对阅读标题并截图；控件、筛选选项、2D 环形菜单、阅读窗指针拖动/键盘移动/复位、双语和邻域切换都有图片。所有节点截图均生成，人工视觉抽查批次末图；不声称逐张人工审阅或无限组合穷举。
- 前端 9 项知识库测试、31 项图谱测试通过；生产构建通过。三维依赖块超过 500kB 的警告仍存在。
- 详细操作、已修问题及未覆盖范围见[逐项截图验收索引](../images/learning-acceptance-2026-10-24/README.md)。原失败/中间态截图保留，并标明后续替代证据。

## 8. 下一课和明确边界

[第十三课完整任务包](13-retrieval-hybrid-and-reranking.md) 已发布，仍为待实现。它将使用本课有效资料比较精确向量、中文关键词、RRF、可替换重排与查询改写；先固定问题集，再测 Recall/MRR/nDCG 与延迟预算。

本课没有实现 ANN 优化、中文分词检索、混合召回、重排、答案引用、真实权限系统、自动异步索引恢复、生产监控或容量测试。当前真实模型调用是最小连通性与生命周期证据；不能描述为企业问答质量已经验收。课程图谱仍是 JSON/邻接图，尚未部署 Neo4j 或 GraphRAG。
