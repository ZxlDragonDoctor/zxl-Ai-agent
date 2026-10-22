# 从 Spring Boot 开始学习 Agent 开发

长期学习入口：[Agent 工程知识图谱](../../zxl-Ai-agent-fronted/src/learning/graph/catalogue.json)（前端 `/learning`），按关系查看完整课程、概念、项目源码、文档证据与参考资料。图谱构建方法本身也作为课程知识维护：[建模与维护指南](graph/README.md)。

本课程直接使用当前项目，面向能写 Spring Boot 接口、Agent 零基础的开发者。目标是独立设计、实现、部署和排查企业级 Agent 应用，并能在面试中解释设计取舍和验证结果。

课程已从原 8 课扩展为 **28 课工程实践路线**，参考 ai-agent-book 的原理与实验，以及 Shannon 的工程机制。每节按“概念 → 当前代码 → 工程改动 → 验收证据 → 独立练习 → 面试复盘”推进，前四课补齐基础，第五课开始优先处理多 Agent 编排与可靠性。

学习节奏已于 2026-10-16 调整：每次按一个完整功能单元给出一批相关任务，先集中讲概念，再连续实现，最后统一检查。断点作为有具体故障时的可选定位方式，不再每改几行就确认一次。

- [完整课程与毕业标准](enterprise-agent-roadmap.md)
- [参考资料、固定版本与源码导读](reference-guide.md)

当前进度：第一课已学完；第二课工具已接通；第三课四项工程任务已实现并通过 48 个专项用例、前端构建和真实模型页面验收。第四课已实现统一 Agent 会话、JSON 历史与上下文恢复，构建与页面观察见[实现记录](04-implementation.md)，第四课实施时未新增或运行自动测试。第五课已实现严格计划校验、依赖数据流与真实报告，见[实现记录](05-implementation.md)；第六课已实现原子状态与失败传播，48 个专项与相关回归、真实页面及故障记录见[验收记录](06-implementation.md)；第七课已实现共享截止时间、协作取消、实际资源所有权和有界容量，见[验收记录](07-implementation.md)。第八课本地工单重试与幂等已实现并通过 12 项新增专项及 4 项预算回归，见[验收记录](08-implementation.md)；第九课独立持久化工单实验已实现，见[实现与证据](09-implementation.md)；第十课[Temporal 对照任务包](10-durable-workflows-with-temporal.md)已发布、工程待实现。真实模型压测、生产授权和多进程恢复仍未覆盖。工程完成与个人独立掌握分别记录；学习图谱入口在前端 `/learning`，实现位于[图谱页面](../../zxl-Ai-agent-fronted/src/views/LearningGraph.vue)。

学习以可见功能、真实执行记录、故障场景和独立改动作为验收依据。断点作为遇到具体故障时的可选定位手段。

已确定的业务主线：**企业知识库与工单协作助手**，贯穿 RAG、权限与租户隔离、人工审批、多 Agent 协作及部署运维实践。

功能盘点更新：2026-10-22。这里区分“代码已实现”“前端已接入”和“实际调用已验证”，不将类名、提示词中的能力描述当成执行能力。

## 项目现在实现了什么

技术栈：Java 17、Spring Boot 3.4.6、Spring AI Alibaba 1.0.0-M6.1；前端为 Vue 3、TypeScript、Vite。独立图片搜索 MCP 服务使用 Java 21、Spring Boot 3.5.3。教学代码以本仓库的实际版本为准，官方最新文档的 API 写法可能不同。

| 能力 | 当前实际实现 | 入口与边界 |
| --- | --- | --- |
| 恋爱助手 | 统一对话中的角色型 Agent；旧 LoveApp 仍提供聊天、Kryo 与 RAG 方法 | 新类型复用 JSON 历史，无文件 / 命令工具；旧接口与历史未自动迁移 |
| 超级智能体 ZxlManus | 模型决定工具调用、Java 执行工具、结果回填上下文、继续执行；最多 20 步 | `/api/ai/manus/chat`，每次请求创建新的 Agent；页面流式显示实际思考、工具状态、参数、结果与最终回答 |
| 本地工具 | 时间查询、文件读写、网页搜索、网页抓取、资源下载、终端操作、PDF 生成、图片分割、终止 | `@Tool` 方法经 ToolRegistration 注册，交给 ZxlManus；时间查询已实际演示，其他工具仍依赖各自服务和配置 |
| 多 Agent 编排 | 编排器、规划、代码、研究、监督共 5 个角色；生成 JSON 计划、按依赖安排任务、并行执行就绪任务、汇总结果 | Agent Hub 接入严格计划校验、直接依赖结果快照与真实流式报告；角色共享 ChatModel，各自有提示词 |
| 任务生命周期 | 原子执行快照、终态保护、失败根因、依赖阻塞、不可变整体结果；共享截止、取消与有界容量 | 第六课 48 项与第七课 30 项受控验收；不代表真实模型压测或跨进程恢复 |
| 工单重试与幂等实验 | 显式错误分类、有限 jitter 退避、共享预算、操作 ID/指纹判重和响应丢失重放 | 第八课新增 12 项专项及 4 项预算回归通过；仅 lesson8-demo profile、本地内存、尚未作为 Agent 审批写入工具 |
| RAG 知识库 | Markdown 文档加载、关键词元数据、Embedding、SimpleVectorStore、查询重写、本地及云端检索方法 | 后端有实现；恋爱助手默认流式入口没有调用这些方法 |
| 会话与记忆 | JSON 会话、独立 runId、完整工具消息转换、最近 8 轮上下文窗口 | 页面支持历史选择与刷新恢复；会话 ID 与历史文件持续有效；长期事实记忆和身份授权尚未实现 |
| 分层记忆 | 工作/情景/语义/过程记忆结构和关键词召回 | HierarchicalMemory 类已写，未看到业务调用；目前是内存容器，不是完整长期记忆系统 |
| MCP | MCP 客户端工具合并机制；独立图片搜索服务提供 Pexels 搜索工具 | 是否可用取决于服务连接和配置，当前课程未验证图片搜索 |
| 图片生成 | 文本生成图片、主题/环境/风格等参数接口 | 后端接口存在，前端尚无专门页面 |
| 可观察性与基准 | Agent 列表、状态、事件接口、运行耗时分位数、成功率报告 | 前端每 5 秒刷新；基准成功表示调用未抛异常，尚不评价答案质量 |
| 双语界面 | 全站静态文案中英文切换、中文默认、语言选择持久化 | 首页、Agent Hub、统一对话、类型选择、会话管理与执行卡片已覆盖 |

本次实际验证：后端状态接口可达，5 个角色均已注册且为 IDLE；同步聊天可以回复，同 chatId 可以回忆演示昵称，换 chatId 后回答“不知道”。这些证据不等同于所有外部工具均完成了运行验证。

2026-10-16 补充：超级智能体已通过真实模型调用 `getCurrentTime(Asia/Shanghai)`。页面持续显示模型思考，工具卡片展示真实返回时间，最终中文回答使用对应结果。该页面的中英文文案随全站语言切换；第五课已替换 Agent Hub 模拟进度，剩余边界见下文。

## 看懂两条核心调用链

普通聊天：

```text
恋爱大师页面
→ AiController
→ LoveApp
→ ChatClient + 系统提示词 + 当前消息 + 会话记忆
→ 模型服务
→ 文本流返回前端
```

单 Agent 执行：

```text
超级智能体页面
→ AiController 创建 ZxlManus
→ BaseAgent.runStream 控制循环
→ ReActAgent.step
→ ToolCallAgent.think 通过 DashScopeAgentStreamer 流式请求模型选择动作
→ ToolCallAgent.act 使用 ToolCallingManager 执行工具
→ 工具结果加入消息上下文
→ 下一步，或结束
```

多 Agent 编排：

```text
Agent Hub 提交目标
→ MultiAgentController
→ OrchestratorAgent
→ PlannerAgent 生成 Plan 和 Task
→ 就绪任务分发给 CodeAgent / ResearchAgent 等
→ SupervisorAgent 汇总
→ 返回整体结果
```

目前这两套 Agent 实现分别位于 `agent` 和 `multiagent` 包。研究/代码角色调用的是 `callLlm`，尚未接入 ZxlManus 的工具执行循环。

## 学习时要识别的实际缺口

1. **能力声明与能力实现。** 第五课已移除 ResearchAgent 未实现的搜索声明；目前只分析给定材料。CodeAgent 生成设计或代码文本，没有文件编辑和执行工具。
2. **依赖顺序与数据传递。** 第五课已将直接依赖的真实输出和执行者注入下游只读输入；计划解析、能力与 DAG 均先校验。
3. **前端进度与真实进度。** Agent Hub 已使用 POST SSE 报告真实任务比例、依赖输入、输出和调用数；全局状态仍每 5 秒刷新，报告尚无持久化与断线恢复。
4. **指标与质量。** 第六课已记录已提交执行快照，并用开始 / 结束时间计算耗时；全局监控、分布式追踪、指标校准与答案质量评估仍需继续完善。
5. **会话、并发与取消。** 第四课会话恢复、第七课截止/取消/并发和第八课本地工单重试幂等已实现；第九课另提供独立的持久工单运行与恢复实验；原多 Agent 编排的整体迁移、工具权限和生产授权仍待完成。凭据必须继续从本地环境注入。
6. **页面与实际路由。** `/chat-room` 已在第四课接入统一 Agent 类型与会话；多 Agent 内部工作角色继续在协作中心展示，不为每个角色新建页面。

这些缺口会作为后续课程的开发题目，每次只解决一个具体问题。

## 课程与完成标准

完整的 28 课主题、工程任务、面试追问和阅读映射统一放在 [企业级 Agent 工程实践路线](enterprise-agent-roadmap.md)。

| 阶段 | 课次 | 要获得的能力 |
| --- | --- | --- |
| 单 Agent 基础 | 1–4 | 模型、工具、循环、会话与记忆 |
| 多 Agent 可靠性 | 5–8 | 依赖数据流、失败传播、取消、并发、重试与幂等 |
| 持久化与恢复 | 9–10 | 任务数据模型、恢复、持久化工作流与选型 |
| 知识与上下文 | 11–14 | 上下文工程、索引、检索、引用与长期记忆 |
| 工具与业务安全 | 15–19 | MCP、Skills、身份与租户、运行隔离、人工审批 |
| 运行治理 | 20–23 | 事件、可观察性、评估、预算与降级 |
| 交付与面试 | 24–28 | 部署、容量、故障演练、项目验收与独立设计 |

原路线的前四课保留；多 Agent 可靠性扩展至第 5–10 课；原 RAG/MCP 和事件/评估拆为更完整的工程专题。学习进度以各里程碑的实际证据为准。

第一课：[模型、上下文与 Agent 循环](01-model-context-and-agent-loop.md)。

第二课：[工具调用：让 Agent 执行真实的 Java 方法](02-tool-calling.md)，预计 60 分钟，添加时间查询工具并观察真实执行和结果回填。

第三课：[执行预算、结束原因与工具结果契约](03-agent-loop-and-results.md)，四项工程任务已实现，[验收记录](03-acceptance.md) 包含实际覆盖范围。

第四课：[会话、历史存储与上下文恢复](04-conversations-and-memory.md)，领域模型、JSON 仓储、管理接口、上下文窗口与执行权已实现；前端统一 Agent 类型、会话列表和恢复。[实现记录](04-implementation.md) 包含当前构建与实测边界。

第五课：[Plan、Task 与依赖数据流](05-plans-tasks-and-dataflow.md)，6 项关联任务已实现；[实现记录与页面证据](05-implementation.md) 区分构建、正常模型演示和未覆盖的故障矩阵。

第六课：[生命周期与失败传播](06-lifecycle-and-failure-propagation.md)，六项任务已实现；[验收记录](06-implementation.md) 包含 48 个专项与相关回归、16.9 秒真实模型演示及可控故障页面。

第七课：[截止时间、取消与并发容量](07-deadlines-cancellation-and-capacity.md)，六项任务已实现；[实现与验收记录](07-implementation.md)说明了 30 项专项测试证据、配置边界和未覆盖的真实模型/生产场景。

第八课：[有限重试与工单幂等](08-retries-and-idempotency.md)，六个关联学习单元及本地工单 API 已实现；[验收记录](08-implementation.md)记录 12 项新增专项和 4 项预算回归。响应丢失后同操作 ID 返回原工单；重启与多实例去重尚未实现。

第九课：[任务持久化与故障恢复](09-persistence-and-recovery.md)，独立 lesson9-demo 实现六表、Flyway、JDBC 短事务、租约/fencing、同库工单去重和有界恢复 worker；默认文件 H2，可配置 PostgreSQL。实际证据与未覆盖范围见[实现记录](09-implementation.md)，不代表原多 Agent DAG 已实现恢复。

第十课：[用 Temporal 对照自建任务恢复](10-durable-workflows-with-temporal.md)，六项完整任务包括权威边界、Workflow/Activity、超时重试、可恢复等待、故障对照和选型 ADR；本课尚未安装 Temporal 或实现 Worker。

## 参考资料

- [Spring AI 工具调用文档](https://docs.spring.io/spring-ai/reference/api/tools.html)：模型提出工具请求，应用执行工具并回填结果。当前官方页面为较新的版本，概念可参考，代码以仓库版本为准。
- [Spring AI 会话记忆文档](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)：上下文记忆与会话记录的用途。
- [ReAct 原始论文](https://arxiv.org/abs/2210.03629)：交替使用推理与动作的基本思路。
