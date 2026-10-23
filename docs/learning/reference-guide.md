# 参考资料与源码导读

核对日期：2026-10-16。配套路线：[企业级 Agent 工程实践与面试路线](enterprise-agent-roadmap.md)。

## 1. 固定阅读基线

| 资料 | 本次核对的 main 提交 | 用法 |
| --- | --- | --- |
| [bojieli/ai-agent-book](https://github.com/bojieli/ai-agent-book) | [`dbc046eb896ac4e39aa19c7774c8bf49583b89a6`](https://github.com/bojieli/ai-agent-book/tree/dbc046eb896ac4e39aa19c7774c8bf49583b89a6) | 课程章节按本次核对的 2.0 目录；实验代码包含可运行、复现说明与设计等不同状态，要逐项查看 |
| [Kocoro-lab/Shannon](https://github.com/Kocoro-lab/Shannon) | [`391b619130281502d227a53dd97238c9791bf174`](https://github.com/Kocoro-lab/Shannon/tree/391b619130281502d227a53dd97238c9791bf174) | 以固定提交阅读，开始运行或迁移时记录实际使用的提交、配置与依赖 |

上面的提交通过公开 GitHub API 查询。后续若切换版本，先记录变更并更新对应实验说明。

## 2. 书的阅读顺序

课程表中的 B1–B10 表示对应章号。章节映射用于选读，不要求按全书顺序一次读完。

| 章节入口 | 对应课程 |
| --- | --- |
| [B1：入门](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter1.md) | 1、3 |
| [B2：上下文工程](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter2.md) | 1、2、11、16 |
| [B3：记忆与知识库](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter3.md) | 4、12–14 |
| [B4：工具](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter4.md) | 2、3、15、18 |
| [B5：Coding Agent 与通用 Agent](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter5.md) | 3、18，毕业后深化 |
| [B6：交互](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter6.md) | 主线完成后的交互扩展 |
| [B7：评估](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter7.md) | 13、14、21–23 |
| [B8：模型后训练](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter8.md) | 进阶选修；按岗位与资源决定 |
| [B9：持续改进](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter9.md) | 22，毕业后深化 |
| [B10：多 Agent 协作](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/book/chapter10.md) | 5 及后续协作选型 |

先看 [学习建议](https://github.com/bojieli/ai-agent-book/blob/dbc046eb896ac4e39aa19c7774c8bf49583b89a6/docs/zh-CN/LEARNING.md)，再围绕当课问题选读。每次阅读留下“当前机制、一个实验、一个失败案例、一项设计取舍”四份笔记。

本次为规划核对了目录、学习建议以及第 2、7 章的相关内容；后续具体授课时继续精读对应章节和实验。

2026-10-23 第十一课再次核对 B2 固定版本中的上下文结构、工具调用与结果关联、历史压缩主题，落地为本项目的确定性预算与原文摘录。未将书中的完整压缩方案或真实模型收益视作本项目已实现；实际范围见[第十一课记录](11-implementation.md)。第十二课任务包依据本地 M6 API 与现有 RAG 源码制定，尚未实现索引生命周期。

2026-10-20 发布[第五课](05-plans-tasks-and-dataflow.md)时，重新阅读了固定提交的第十章中上下文共享、协作拓扑与分工收益部分，以及 Shannon DAG 的依赖校验、执行模式选择分支；未切换参考版本、未运行参考项目。课程中的 Java 结构与显式拒绝无效计划策略由本项目定义。

## 3. Shannon 源码阅读入口

以下入口在固定提交中核对过，S 编号与课程表对应。

| 编号 | 文档与代码 | 要回答的问题 |
| --- | --- | --- |
| S1 | [README 架构](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/README.md#architecture)、[工作流架构](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/docs/multi-agent-workflow-architecture.md) | 网关、编排、执行与模型服务各自负责什么？哪些边界值得在 Java 项目中保留？ |
| S2 | [DAG 工作流](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/go/orchestrator/internal/workflows/strategies/dag.go) | 计划何时校验，依赖怎样影响执行，重试与暂停检查放在哪里？ |
| S3 | [Skills 系统](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/docs/skills-system.md) | 业务策略如何装载、版本化并限定工具范围？ |
| S4 | [会话工作区](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/docs/session-workspaces.md) | 沙箱究竟限制哪些资源？哪些工具还需要独立授权与网络控制？ |
| S5 | [审批中间件](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/go/orchestrator/internal/workflows/middleware_approval.go) | 审批请求、等待信号和超时怎样关联？动作参数与审批者应怎样校验？ |
| S6 | [网关认证中间件](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/go/orchestrator/cmd/gateway/internal/middleware/auth.go) | 身份在入口怎样建立，下游拿到哪些可信信息？ |
| S7 | [流式事件接口](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/docs/streaming-api.md) | 实时流与持久化历史怎样配合，重连、游标和保留期限怎样影响恢复？ |
| S8 | [预算中间件](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/go/orchestrator/internal/workflows/middleware_budget.go)、[预算管理器入口](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/go/orchestrator/internal/budget/manager.go) | 调用前的检查、背压、并发预留与调用后的核算怎样衔接？管理器将在预算课进一步追踪 |
| S9 | [部署配置入口](https://github.com/Kocoro-lab/Shannon/blob/391b619130281502d227a53dd97238c9791bf174/deploy/compose/docker-compose.release.yml) | 服务、数据和配置依赖有哪些，部署与恢复如何复现？具体运行留到部署课 |

本次进行了文档和部分关键源码的静态核对；没有部署 Shannon，也没有运行其测试或宣称通过性能验收。

### 已发现的参考边界

1. **配额裁剪有开源边界。** 固定提交的 `middleware_budget.go` 中，`CapBudgetAtQuotaRemaining` 明确写为开源版空实现，直接返回传入的 `agentMax`。这不表示整个预算模块都未实现；租户剩余额度控制仍需逐条追踪和验证。
2. **文档默认值需要与实现核对。** 工作流文档和审批中间件对默认等待时间的描述存在差异。课程使用显式配置，并验证触发、拒绝、过期和恢复路径，不照抄默认数字。
3. **工作流重试不等于业务幂等。** 已看到 DAG 的 Activity 重试配置；外部写入仍要靠操作 ID、持久化记录和外部接口约束去重。
4. **流式事件有保存边界。** 流式文档区分实时事件存储与重要历史事件存储；不能据此推断所有流式片段永久可恢复。

这些观察用于训练阅读和验证工程实现的能力。实际使用前还要结合目标场景、版本与配置做运行验收。

## 4. 从阅读走到工程实践

每个参考机制都按下面步骤落地：

```text
提出业务问题
→ 阅读原理与架构
→ 定位实现及边界
→ 记录 Java 方案与取舍
→ 实现一个最小闭环
→ 用正常与失败案例验收
→ 记录结果和局限
→ 整理面试追问
```

例如学习审批时，最终交付应包括审批状态、绑定参数、授权校验、超时规则、审计与重复回调处理。学习预算时，应同时记录估算值、模型返回用量、并发预留与实际结算的差异。

开始具体实验时，再查看相关技术的官方文档、当前 SDK 和兼容性要求。当前 Java 主项目使用 M6 依赖，参考项目中的其他语言接口用于理解设计，代码迁移需要单独适配。
