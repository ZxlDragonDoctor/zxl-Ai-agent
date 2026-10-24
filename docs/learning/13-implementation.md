# 第十三课实现与验收：检索、混合召回与重排

更新日期：2026-10-24。状态：**独立教学链路已实现，专项测试、真实PG、真实22题评测与主要页面流程已验收。** 工程图谱第十三课为completed，个人掌握状态保持独立；本批26套138项后端回归及44项前端测试通过，学习图谱截图已归档。

## 1. 先理解本课落地了什么

第十二课解决“文档如何成为有效版本”；本课解决“在当前有效且允许使用的资料中，找出哪些片段最可能回答问题”。向量路径保留原问题，关键词路径可以使用一次确定性词汇补充；两路按 RRF 合并，按需重排，返回前再次检查有效版本。

```text
原问题 → 原问题Embedding → PG精确向量候选 ─┐
       → 可选词汇补充 → PG关键词子串候选 ──┴→ RRF
         → 当前版本检查 → 可选规则/HTTP重排 → 最终版本检查 → TopK
```

本课输出是候选证据，不是带引用的最终业务回答。结论支持性、无依据拒答、长期事实记忆属于[第十四课任务包](14-citations-abstention-and-memory.md)，尚未实现。

## 2. 代码职责和真实实现边界

路径相对 `src/main/java/com/zxl/zxlaiagent/knowledge/`：

| 文件/目录 | 实际职责 |
| --- | --- |
| `retrieval/RetrievalController.java` | 请求字段白名单、严格类型和范围校验、结构化错误响应 |
| `retrieval/RetrievalService.java` | 双路召回、RRF、当前版本检查、受限重排与阶段报告 |
| `retrieval/QueryTerms.java` | 固定词典、英文/错误码与中文 bigram、确定性词汇补充 |
| `retrieval/RankFusion.java` | 去重、从1开始的排名贡献、固定并列顺序 |
| `retrieval/Reranker.java` | 可替换重排端口、规则实现、候选身份和分数校验 |
| `retrieval/HttpReranker.java` | DashScope HTTP重排协议、显式模型与部署标签、超时与响应校验 |
| `retrieval/RetrievalDeadline.java` | 请求级共享剩余预算、过期和取消 |
| `evaluation/` | 独立合成语料、固定标签、四策略评测及聚合/逐题结果 |
| `KnowledgeDatabase.java` | 剩余预算传递到连接获取、SQL与锁等待；有界执行及晚到连接关闭 |
| `Lesson12Demo.java` | 延续隔离教学入口，注册检索与评测组件 |

前端复用 `/knowledge`：`RetrievalLab.vue` 展示检索，`RetrievalEvaluation.vue` 展示评测；未新增聊天类型专用页面。

- PostgreSQL **16.15 + pgvector 0.8.6** 为正式存储。精确余弦检索沿用第十二课模型身份、维度、active revision及删除过滤；没有实现 ANN 索引。
- 关键词实现是固定词典 + 中文 bigram + 参数化子串匹配，提供可解释教学基线；**不是 BM25，也不等于通用中文全文搜索**。没有增加未固定版本的外部分词器。
- `rewrite=true` 只执行确定性词汇补充，用于关键词通道；向量始终使用原问题。没有调用 LLM 改写，也没有将旧 `QueryRewriter` 误标为本课已验收改写器。
- `generation` 当前映射文档清单 `d.version`，属于文档版本围栏，不是全库统一的索引快照编号。
- 服务从构造时绑定可信教学租户，HTTP请求不能提交租户覆盖。当前固定演示身份**不代表生产认证、用户ACL或完整跨租户安全验收**。
- 重排前和返回前都复查当前有效候选，失效后按实际数量返回短缺；当前没有自动补取至TopK的循环。
- 重排在线程池排队后、真正调用外部服务前再次检查候选，删除或换版后不外发旧文；已有先失败后修复的真实PG回归。模型空间不匹配返回 `NO_COMPATIBLE_INDEX_PROFILE`，不会伪装成成功零命中。

## 3. 请求、结果与失败契约

独立教学入口默认监听本机 `18132`，不带 `/api` 前缀。前端通过开发代理/配置接入，路径以浏览器真实请求为准；原主应用的 `/api` 基路径不能机械套到隔离入口。

| 方法 | 隔离入口路径 | 用途 |
| --- | --- | --- |
| POST | `/ai/learning/knowledge/retrieval` | 单次向量、关键词或混合检索 |
| GET | `/ai/learning/knowledge/evaluation/status` | 固定评测语料和模型准备状态 |
| POST | `/ai/learning/knowledge/evaluation/seed` | 初始化独立评测语料，JSON `{ "real": false }` 或显式真实模式 |
| POST | `/ai/learning/knowledge/evaluation/run` | 执行固定问题评测，JSON含 `split`、`topK`、`real`、`budgetMs` |

检索示例：

```json
{
  "query": "VPN提示809怎么办",
  "mode": "hybrid",
  "topK": 5,
  "candidateLimit": 20,
  "rerank": true,
  "rewrite": true,
  "budgetMs": 10000
}
```

请求限定：query非空且不超过2000个Java字符；mode为vector/keyword/hybrid；topK为1–5；candidateLimit在topK–20间；budgetMs为100–10000。未知字段、错误类型与非法范围返回400 `INVALID_RETRIEVAL_OPTIONS`。

响应包括traceId、原问题、实际词汇补充、模式、Embedding/重排身份、实际耗时、阶段、降级、短缺与候选。候选带documentId、revisionId、chunkId、generation、文本、原文UTF-16范围/行号、通道、距离/rank、融合分数和可选重排分数。

允许单路降级时明确记录失败通道；全部通道失败返回503 `ALL_RETRIEVAL_CHANNELS_FAILED`。预算过期/取消/服务失败与成功查询后的 `NO_CURRENT_MATCHES` 区分；有效候选不足返回 `INSUFFICIENT_CURRENT_CANDIDATES`，不使用旧版本凑数。重排异常可退到已校验RRF顺序并记录 `RERANK_FAILED_OR_BUDGET_EXHAUSTED`；总截止真正耗尽仍按失败处理。

## 4. 预算、资源与外部模型

共享截止覆盖Embedding、连接等待、SQL、重排与最终检查。`BudgetedConnection` 把调用者剩余deadline传入连接获取，采用4工作线程+8排队上限，取消/超时后关闭晚到连接；重排采用2工作线程+8排队上限，为最终数据库检查预留预算。线程中断和取消传播有专项用例；这些是教学容量上限，不构成生产吞吐承诺。

HTTP重排最多20候选，总文本上限20000个Java字符，不做隐式截断；响应必须返回完整且唯一的候选index与有限[0,1]分数，不能注入候选。HTTPS端点、模型、API Key和部署标签均由服务端配置，禁止重定向；仅本机协议测试允许loopback HTTP。

协议依据：[阿里云Model Studio文本重排API](https://www.alibabacloud.com/help/en/model-studio/text-rerank-api)。本批真实探针模型为 `qwen3-rerank`，应用部署标签 `acceptance-20261024`。该标签用于本次应用配置审计，不能据此宣称供应商权重已被不可变锁定。

## 5. 固定问题评测

`evaluation/` 固定 **22题：9题调参、13题留出**，分别比较关键词、精确向量、RRF、RRF+重排。结果提供逐题候选、人工相关标签、Recall@K/MRR/nDCG、禁止候选数、阶段耗时和降级。当前聚合约定：Recall与nDCG排除无答案组，MRR将无答案按0纳入；报告保留无答案分组，不以任意满分补分母。比较外部实现前必须先对齐这些口径。

评测语料使用独立演示租户 `demo-evaluation` 和 `demo-evaluation-real`，不导入、删除或覆盖用户自己的资料。每次批次默认60000ms，允许1000–120000ms；单查询仍最多10000ms，串行执行并共享查询向量缓存以便四策略比较。批次预算不等同于每题获得完整新预算；报告实际失败/降级及运行范围。

确定性demo散列Embedding和规则重排只证明控制流/算法。`real=true` 必须有真实HTTP模型配置，不能静默使用demo后称真实语义通过。该小型合成集不能外推生产质量或证明混合/重排必然提升。

## 6. 启动与配置

延续[第十二课运行说明](12-implementation.md)，入口为 `com.zxl.zxlaiagent.knowledge.Lesson12Demo`，Spring profile为 `lesson12-demo`，默认端口18132，仅绑定本机回环。

| 环境变量 | 用途 |
| --- | --- |
| `LESSON12_DB_URL` / `LESSON12_DB_USER` / `LESSON12_DB_PASSWORD` | 真实PG连接，必须显式注入 |
| `LESSON12_PORT` | 可选教学HTTP端口 |
| `LESSON12_EMBEDDING_MODE` | demo或http；demo仍操作真实PG |
| `LESSON12_EMBEDDING_ENDPOINT` / `LESSON12_EMBEDDING_API_KEY` | http模式显式HTTPS端点与密钥 |
| `LESSON12_EMBEDDING_MODEL` / `LESSON12_EMBEDDING_REVISION` / `LESSON12_EMBEDDING_DIMENSIONS` | 实际模型、部署标签与维度，文档和问题必须同空间 |
| `LESSON13_RERANK_MODE` | rule或http，默认rule |
| `LESSON13_RERANK_ENDPOINT` / `LESSON13_RERANK_API_KEY` | http模式显式HTTPS端点与密钥 |
| `LESSON13_RERANK_MODEL` / `LESSON13_RERANK_REVISION` | 本次真实配置qwen3-rerank / acceptance-20261024 |

在IDE运行该main类时注入变量即可。构建或运行命令不应把密钥直接写入参数、日志、截图或文档。数据库连接失败直接报错，没有内存fallback；不启动原主应用的MCP/恋爱资料内存初始化链路。开启真实模式会调用外部服务，需记录本次实际用量与失败。

## 7. 验证证据

| 层次 | 当前已收到的证据 | 边界 |
| --- | --- | --- |
| 自动测试 | 检索26项、评测8项，共34项无重叠专项通过 | 检索含连接预算3、HTTP Embedding 3、HTTP重排3、算法5、API 2、PG 9、排队删除PG 1；评测含指标3、服务4、PG 1。最终26套138项回归通过，失败/错误/跳过均0；34项包含在138项中，不重复累计 |
| 真实PG | PostgreSQL 16.15 + pgvector 0.8.6；检索与评测PG专项通过 | 验证版本/删除/租户与模型空间过滤、排队删除竞态；固定身份不等于生产ACL |
| 真实HTTP重排探针 | 1次调用、2条合成候选，VPN809先于printer809；首条分数0.8435542106896945 | 只证明该次协议/排序链路，不证明22题质量提升 |
| 固定22题真实模型评测 | 全量完成66474ms；22次Embedding、44次查询缓存命中、22次真实qwen3-rerank；8组错误/禁止块均0 | 下表及原始JSON记录质量；单次串行共享缓存，不是生产容量或冷启动性能基准 |
| 前端构建与图谱 | 13项knowledge API与31项graph，共44项通过；生产构建退出0 | 最终构建与测试重新执行；图谱95节点134关系28课校验通过，筛选/关系/阅读卡/中英文有截图 |
| 页面逐按钮与截图 | 检索与评测主要流程实际点击、截图已取得 | 覆盖模式/TopK/预算、来源打开关闭、8策略逐题、成功零命中、100ms失败恢复及真实初始化/运行/导出；详见[逐操作截图索引](../images/lesson13-acceptance-2026-10-24/README.md) |

真实报告：[real-evaluation.json](../images/lesson13-acceptance-2026-10-24/real-evaluation.json)。资料集版本 `synthetic-helpdesk-v1`，TopK=3、split=all、批次预算120000ms、并发1。模型为 `text-embedding-v3`、1024维与 `qwen3-rerank`，应用部署标签均为 `acceptance-20261024`。

| 留出集策略 | Recall@3 | MRR@3 | nDCG@3 |
| --- | --- | --- | --- |
| 关键词子串 | 0.9090909090909091 | 0.8461538461538461 | 0.9183314735913406 |
| 精确向量 | 1 | 0.8076923076923077 | 0.9690001640729466 |
| RRF | 0.9545454545454546 | 0.8076923076923077 | 0.9466470298694799 |
| RRF+真实重排 | 0.9545454545454546 | 0.8076923076923077 | 0.9532033147209976 |

本次纯向量的留出Recall与nDCG最好；重排相对RRF的nDCG有所提高，但没有超越纯向量，不能预设混合必胜。调参集与逐题降级/错误详见JSON。本课没有生成最终答案，以上指标不代表答案正确率。截图目录为 `docs/images/lesson13-acceptance-2026-10-24/`，[完整索引、语言切换及学习图谱验收](../images/lesson13-acceptance-2026-10-24/README.md)已记录。另一次单次查询出现真实重排超时并显式回退，见截图52；不混入上述22题报告。

## 8. 尚未覆盖与后续学习

未实现ANN、BM25、通用中文分词器、LLM改写、生产身份/ACL、全库generation快照、自动TopK补齐、最终答案生成、引用支持性校验或长期事实/向量记忆。外部服务故障和单路降级的算法测试不能证明真实供应商在所有错误情形下的资源清理。

下一课完整任务见[第十四课：引用、拒答与长期记忆](14-citations-abstention-and-memory.md)。学习时先观察本课返回的真实来源、通道、短缺与降级，理解为什么“排在前面”尚不等于“能够支持最终结论”。
