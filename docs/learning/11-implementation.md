# 第十一课：实现、操作与验证边界

日期：2026-10-23。课程概念见[上下文工程导读](11-context-engineering.md)，下一课见[知识接入任务包](12-knowledge-ingestion-and-indexing.md)。本课与第十课 Temporal 选型实验独立，第十课仍待实现。

## 1. 实现了什么

| 能力 | 实现位置 | 可核查行为 |
| --- | --- | --- |
| 确定性预算组装 | `conversation/context/ContextAssembler.java` | 系统、当前问题、nextStep、工具 schema 与输出/执行预留先占位；不足明确失败 |
| 原子裁剪 | 同上及 `ConversationMessageMapper` | 最近完整 run 连续保留，工具调用和结果不被单独截断；不完整/running run 排除 |
| 摘录式摘要 | `ContextAssembler` | 对被裁剪轮次中的用户/助手普通文本取最多 96 个 Unicode code point 前缀，保留角色、run/message ID、完整原文 SHA256；不摘取工具结果 |
| 分类预算 | `ContextAssembler.Limits` | 历史总量、工具结果子预算、摘要和外部证据各受上限及总预算约束 |
| 可替换估算器 | `ContextAssembler.UnitEstimator` | 默认 `utf8-bytes-plus-16-v1`；每段固定 16 units 开销，报告策略与模板版本 |
| 统一会话接入 | `ConversationContextWindow.prepare`、`AgentConversationService.chat` | 创建实际 Provider Agent 后读取提示与工具定义，组装初始历史；完成捕获仅保存当前 run 的真实消息 |
| 离线预算预览 | `ContextPreviewController` | 仅 `lesson11-demo` profile，预览现有会话下一条问题的预算和摘录，不调用模型、不启动 run |

`Plan.history` 是交给 Agent 恢复的派生消息，可以含 USER 角色的摘录/证据。系统提示与当前问题由已有 Agent 调用链设置和追加，不能把它们再当历史重复添加。原始 JSON 会话不会被预算裁剪。

历史选择采用连续最近完整轮次：从新到旧，遇到第一个装不下的轮次停止；不会跳过超大新轮次后继续塞更旧轮次。报告中的 `selectionPolicy` 明示这一取舍。`toolResultUnits` 是 `historyUnits` 的子集，不能在总量中重复相加。

摘录只从本次原始 Conversation 生成，不接收客户端传入的 Summary 作为可信摘要；外部 `Evidence.sourceId` 则由调用方提供，组装器保留与校验格式，不证明来源授权或内容真实性。当前统一聊天未自动接入 RAG，外部证据契约通过专项测试验证，后续索引/检索课才提供真实候选资料。

## 2. 默认预算

单位均为估算 units，**不是模型 token 配额**。

| 项目 | 默认值 |
| --- | --- |
| 总窗口 | 32768 |
| 输出预留 | 4096 |
| 后续执行预留 | 4096 |
| 历史上限 | 16384 |
| 其中工具结果上限 | 8192 |
| 摘录上限 | 2048 |
| 外部证据上限 | 4096 |
| 历史完整轮次 | `agent.conversation.context-runs`，默认 8，范围 1–50 |

分类上限是竞争同一总量的上限，不是保证预留；实现先选择历史，再摘要，再证据。真正业务如果证据优先，应通过测试后调整策略。Limits 可供 Java 调用方配置；本次没有添加全套动态配置页面。

## 3. 怎么观察

统一会话 `/chat-room` 的下一次请求会使用新的初始上下文组装。页面不会自动出现新预算面板，也没有为第十一课新建独立 Agent 页面。

需要查看明细时，在已有本地启动方式中增加 `lesson11-demo` profile，保留实际使用的本地配置 profile；这会启用下面的只读预览入口。**本批未启动整个应用作页面演示**，避免把启动时已有 RAG/模型初始化混作离线验证。

已有会话 ID 可从统一会话接口或页面获得。在使用 `/api` context-path 的主应用中：

```http
POST /api/ai/learning/context/preview
Content-Type: application/json

{"conversationId":"替换为现有会话UUID","message":"请根据最新的工单状态继续处理"}
```

去掉 `/api` 与否取决于启动环境的 context-path。未启用实验 profile 时不注册该接口。预览会实例化对应 Provider 的 Agent 以读取定义，但不会运行 Agent 或调用模型；已有 Provider 构造器本身必须继续保持无业务副作用。

响应包含 `summary` 和 `budget`，重点阅读：

- `selectedRunIds`：被原样恢复的完整轮次。
- `droppedRunIds`：因预算/轮数没有进入原样历史的完整轮次。
- `excludedIncompleteRunIds`：运行中或消息组不完整的轮次。
- `sourceMessageIds`：历史和摘录对应的原始消息。
- `selectedEvidenceIds/droppedEvidenceIds`：外部候选证据取舍；当前会话接入默认没有外部证据。
- `totalReservedUnits/limit`：包含预留的离线预算。
- `templateVersion/estimator/selectionPolicy`：复现此次组装所需的策略说明。

可以复制会话资料在单元测试中调小 Limits，比对保留轮次和摘录出处。不要通过修改运行目录中的真实历史文件来伪造验收。

该入口沿用项目本地单用户会话范围，没有新增可信认证，不是生产开放 API；会话 UUID 不应被当作授权机制。第十七课补齐认证与租户链路。

## 4. 自动测试与验收

本批先增加预算测试并看到预期失败，再实现组装器；会话接入与 API 测试随后补充验证，不声称所有路径均严格测试先行。实现初期预期失败和后续通过的日志位于忽略的 `target/lesson11-*.log`，不将运行产物提交到 Git。

主 Agent 独立重跑下面的定向回归，退出码 0；逐一核对本次 Surefire XML，**84 项通过，失败 0、错误 0、跳过 0**。

```text
mvn -q "-Dtest=ContextAssemblerTest,ConversationContextIntegrationTest,ContextPreviewApiTest,PersistentWorkflowRepositoryTest,LearningPersistenceApiTest,PersistentWorkerTest,TicketReliabilityTest,TicketApiTest,ExecutionControlTest,BaseAgentBudgetTest,AgentOutcomeTest" test
```

| 测试 | 数量 | 实际验证 |
| --- | --- | --- |
| ContextAssemblerTest | 13 | 必要预算、完整工具组、Unicode、原文 hash、证据预算、替换估算器、精确边界、轮次选择、溢出与重复来源 |
| ConversationContextIntegrationTest | 4 | 真实协调服务配本地 FakeAgent；投影接入、当前轮捕获、保留原历史、已有/新会话超限不写入，正常新会话只创建一个 Agent 并释放执行权 |
| ContextPreviewApiTest | 1 | standalone MockMvc + 临时真实 JSON 仓储，200/400/404、文件字节不变；不是联网浏览器测试 |
| 第八/九课和 ExecutionControl 回归 | 34 | 持久化、跨 JVM、并发去重、回滚、过期取消、重试与预算 |
| BaseAgentBudgetTest / AgentOutcomeTest | 32 | 既有 Agent 步数预算和终止结果 |

专项实现过程中，第一次测试因新类型不存在编译失败；提供最小契约后出现真实预算断言失败及未实现分支错误，再补逻辑通过。`target/lesson11-red-behavior.log` 是行为红灯证据；中间 `lesson11-green.log` 名称不代表该次已经通过，最终以 `lesson11-context-tests.log` 和独立 `lesson11-root-regression.log` 为准。

两轮只读审查分别核对需求边界与代码质量。质量审查发现“无 conversationId 的超预算请求会遗留空会话”：先新增用例，实际看到仓储 create 被调用的失败，再将空会话预算预检前移到落盘之前，补正常短输入创建回归；红灯见 `target/lesson11-new-conversation-red.log`。审查不是额外运行证据。汇总不包含所有现有测试：本批没有运行会启动外部模型的全量 SpringBoot 测试。

前端验证命令（在 `zxl-Ai-agent-fronted` 目录）：

```text
npm run test:graph
npm run build
```

图谱额外使用实际 `model.ts` 的 `validateCatalogue` 检查 ID、端点与先修无环，并逐一检查 evidence/path 来源存在；不操作个人学习掌握状态。

结果：**25 项图谱测试通过；生产构建通过；87 个节点、123 条关系、28 课校验通过**。构建保留既有 3D 代码块超过 500 kB 的提示，未作性能达标承诺。

## 5. 证据的实际范围

- 自动测试验证可重复的组装与会话捕获规则，不代表真实模型回答质量已提高。
- 本批没有真实模型调用，未测真实 token、费用、时延、输出长度或缓存命中；不宣称有某个百分比收益。
- 默认估算器不是供应商 tokenizer，工具定义的字符串估算也不是线上请求序列化精确成本；生产需校准、实际 usage 核算及错误兜底。
- 这次接入的是统一会话 **run 开始时**的历史投影。执行中新增工具结果、nextStep 累积、多步扩张仍需每次调用前重新控制；固定 executionReserve 不能给任意长流程提供硬保证。
- 提示模板版本指本课摘录包装模板；没有把所有 Provider 的业务系统提示都迁移到模板仓库，也未实现提示缓存治理。
- 没有自动构建长期用户事实记忆，也未实现语义摘要模型、关键事实完备性评分或相关记录语义检索。只证明引用可追溯，不能证明全部业务事实正确且不遗漏。
- 图谱内容与关系已同步；由于此前本地页面访问限制，本批不重试绕过，不宣称筛选、阅读卡、语言切换、拖动或 WebGL 已人工验收。
- 第十二课只有完整任务设计；第十课、真实知识索引、生产授权、Neo4j/GraphRAG 均不会随本课自动完成。
