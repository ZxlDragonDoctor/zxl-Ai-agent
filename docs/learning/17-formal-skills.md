# 第十七课补充：把 Skill 升级为当前身份执行

## 概念：策略版本不是授权版本

Skill 固定业务步骤、工具范围、输出草稿格式和预算。它不能代替当前用户权限。正式执行需要 `skill.execute`、`agent.execute`、`knowledge.read`，同时核实当前会话与运行归属；运行开始选择的策略版本也不能冻结之后的用户权限。

## 本项目的实现

- 旧课堂 v1/v2 原样保留；正式目录单独注册审核后的 v3/v4，只引用知识 MCP v2 的模型 schema 和摘要。
- 正式默认版本使用 `agent.skills.formal-default-version`，默认 v3。旧 `LESSON16_DEFAULT_VERSION=v1` 不影响正式模式，显式旧版或未知新版拒绝启动/选择，不静默回退。
- v3 输出 ticket-draft-v1，v4 输出 ticket-draft-v2（NORMAL 优先级）。两者都是草稿，没有真实工单写入。
- `ScopedSkillRunner.runWithAuthorizedPublisher` 显式接收可信 ActorRef、conversationId 和 runId，经 ScopedResearchFactory 传递同一 Java 权限范围到模型、MCP 与原文。
- 步骤为澄清、查询、复核建议、草稿。缺产品/错误码时先澄清，不调用模型；无有效证据时 NO_EVIDENCE，不能继续编造草稿。
- 正常事件先核当前权限，再交给已绑定 BaseAgent 的实际队列/消费 gate，不在外层重复持授权事务锁调用 publisher。

## 为什么取消终态也要单独处理

独立审查发现：中途超时/取消后，错误分支再次检查已经耗尽的预算，导致无法形成正确 Skill 终态。先用 13 项专项中的执行中取消、超时、已关闭 publisher 和 typed MCP_CANCELLED 用例复现，再把中性状态归并与发送分开。

中性失败结果不延长任何模型或工具预算，也不包含迟到建议/草稿。当前权限仍需检查；BaseAgent 仍负责实际发送 gate。已关闭连接可丢弃中性帧，但本地取消结果不应因此丢失，不能声称客户端已收到该帧。

## Provider 声明与敏感结果落盘

Java Provider 通过 `executionPermissions()` 声明额外动作，统一会话服务组合基础动作与会话/运行归属。未来类型通过注册扩展，不靠集中 switch；浏览器、模型不能覆盖这些声明。

知识/研究声明 `knowledge.read`，Skill 同时声明 `skill.execute`。实际发布及最终保存都重新检查整个组合；仅有 `conversation.writeOwn` 不足以保存已经失去权限的敏感结果。拒绝时只在仍允许写本人会话的情况下记录空答案、空事件的中性失败终态；会话权限也撤销时不再保存。

## 证据与待验收

2026-10-30 明确选择的 **16 类 83 项通过，0 失败/错误/跳过**，含正式 Skill、Provider 发布/保存、流边界、知识/研究 Provider、配置及旧课堂回归。日志 `target/lesson17-formal-skill-final-20261030.log`，精确范围见 [测试记录](../images/lesson17-business-2026-10-30/skill-tests.json)。不能与本批其他测试简单累加为全仓库通过。

此前空证据测试的 fixture 漏掉 ToolResult 必填 message，严格校验正确拒绝；修正 fixture 后仍保留 NO_EVIDENCE 和 0 草稿断言，没有放宽生产契约。发布/保存 3 项也先复现旧服务忽略 Provider 动作，再修实现。

这些是自动化证据；新常驻主应用、真实模型下的正式 Skill 和页面逐按钮截图仍需独立验收。早先研究助手截图不是 Skill 的截图。课程保持进行中，个人掌握状态不自动改变。
