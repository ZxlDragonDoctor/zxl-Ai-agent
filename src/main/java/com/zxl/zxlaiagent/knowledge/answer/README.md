# 第十四课回答模块

`AnswerService` 复用第十三课 `RetrievalService`；`AnswerController` 只在 `lesson12-demo` 中注册。统一会话通过 Provider 调用本地 worker，不另建聊天页面。

## 请求与输出

`POST /ai/learning/knowledge/answers`

```json
{"question":"VPN 809 需要检查哪些端口？","conversationId":"demo-session","mode":"demo","budgetMs":30000,"profile":"alice"}
```

- `mode` 明确区分 `demo` 与 `real`。后者没有显式模型配置时返回 503/SERVICE_FAILURE，不偷偷用规则回答。
- `profile` 只能为服务器已登记的 `alice` / `bob`；这只是固定演示身份映射，不是用户认证。知识范围由构造服务的固定 tenant 决定。
- 五种业务状态：`ANSWERED`、`PARTIAL`、`NEEDS_CLARIFICATION`、`INSUFFICIENT_EVIDENCE`、`SERVICE_FAILURE`。最后一种用 HTTP 503，其余用 200；请求格式错误为 400。
- 每条结论有 `evidenceIds`、`support`、简短业务理由；不输出模型思考链。被移除的虚假结论正文不会展示为答案，返回移除数量与顶层原因。
- `evidence` 保留本请求 `snapshotId`、`E1…En`、document/revision/chunk、`generation`、原始 UTF-16/行号、真实截取文本和 SHA-256。引用来源与阅读文本使用同一份对象。

## 顺序与边界

1. 固定身份读取最多 5 项/1000 字符的已确认偏好；偏好不得作为企业事实来源。
2. 缺少 VPN 错误码时使用明确的演示澄清规则。它不是通用意图识别器。
3. 混合检索候选，再从真实 PG 原文核对范围、行号和当前版本，签发本请求证据编号。证据总量至多 4000 UTF-16 单元，单条至多 1200；截断保留真实子区间，不截断代理对。
4. 隔离包含显式“忽略规则 / 系统指令 / ignore previous instructions”等覆盖指令的证据块，未隔离的证据才允许进入生成器。返回 QUARANTINED_UNTRUSTED_SOURCE；有其余合法结论时标记 PARTIAL。此 v2 保守规则针对已知失败开发，有误拦风险，不是通用注入识别器。
5. 生成结构化原子结论；校验快照、格式、未知/重复/未声明引用、数值及保守否定规则。
6. 只对引用的当前证据调用支持性端口。真实模式调用真实模型，替身的逐字包含规则不代表语义校验。
7. 不支持的结论直接移除，不无限修正。发布前再次核对偏好与全部证据。删除、换版、同原文删除后重导的 generation 变化都使旧快照失效。

生成与评判共享请求 deadline，使用 2 个 worker、8 项队列；队列超载明确失败。调用者超时后取消任务，忽略中断的第三方端口仍可能占用有界 worker，不能据此声称远端计算已经停止。不存在无限后台线程。

`HttpAnswerModel` 使用显式 HTTPS endpoint/key/generator/judge/revision，拒绝跳转，读取响应最多 64 KiB，不记录密钥或原始响应。生成与评判是分开的调用，可以使用同一模型，不能称为两个独立评判模型。结构化用户消息通过第十一课 `ContextAssembler.utf8Units` 限制在 24576 UTF-8 字节；这是字节边界，不是供应商 tokenizer，也不是完整上下文窗口计量。

HTTP 生成不接收旧会话历史；`conversationId` 用于关联，统一会话原有的历史保存与上下文预算继续独立工作。本模块不声称已经实现跨轮指代解析。固定 demo 只抽取匹配资料并做逐字包含检查，便于检查拒答/引用控制流；真实语义能力必须查看真实模型对照报告。

## 固定支持性标注

`SupportFixtures` 固定 `support-helpdesk-v1`，12 条人工合成标签（6 tuning / 6 holdout），包含数值、否定、半句支持、遗漏条件、多证据、无关回答、未执行动作、提示注入和无资料制度。先声明门槛：holdout 至少 5/6 正确，且不存在不支持结论被接受。`run` 保存每条期望、实际与人工理由，失败标明 SERVICE_FAILURE；规则替身运行不能作为真实模型验收。单次小规模结果不构成生产质量或统计保证。

真实 PG、自动测试、模型对照、逐按钮截图的最终证据统一记录在课程实施文档和验收索引，不能以本模块文件存在代替验收。

原始 v1 真实模型评判报告必须保留。已知失败的 v2 回归用于证明程序隔离生效，不能把同一留出集重跑后较好结果当成未调优的语义门槛通过。

生成契约 prompt-v2 增加显式 missingInformation（至多5项、单项300字符、合计1000字符）。有支持结论且仍有未覆盖子问题时返回 PARTIAL 并列出缺口；无支持结论时不足。真实生成器被要求逐项核对，但模型自报缺口仍可能漏检，不声称通用子问题覆盖已经形式化证明。demo只为本课 VPN+年假固定复合案例补充有限关键词缺口规则。支持性judge保持prompt-v1，原始真实留出报告不因生成契约升级而改写。

生成prompt-v2明确映射reply_language（zh/en）、reply_detail（concise/detailed）、reply_format（bullets/paragraphs），当前明确指令优先；自然提问语言不自动覆盖语言偏好。提示优先最多3条直接相关原子结论以控制judge调用数，解析硬上限仍8。是否实际遵循偏好须以真实模型/页面记录验证，HTTP请求体测试只证明规则已发送。保守否定规则可能移除非逐字否定改写或跨语言否定翻译，属于已知误拒边界。
