# 第四课：会话、历史存储与 Agent 上下文恢复

业务目标：用户刷新页面或重启服务后仍能继续原会话；新建会话后上下文隔离。前端由助手负责，主要学习与实现 Java 后端。

2026-10-19 工程实现已落地：[实现记录、代码地图与页面证据](04-implementation.md)。本轮完成前后端构建与核心页面观察，未新增或运行自动测试；故障与并发场景的覆盖边界在记录中单列。

用户补充的结构要求：对话页面统一，恋爱助手作为一种 Agent 类型提供测试示例。`ConversationAgentProvider` 注册类型，`Conversation.agentType` 在创建时固定；新增类型复用相同的会话、存储与页面。

建议一次完成下面 6 项关联任务，预留 2–3 小时，集中检查后再接页面。第三课解决了一次执行怎样结束，本课解决多次执行怎样属于同一个会话。

## 1. 先理解四种不同的数据

| 数据 | 用途 | 当前项目情况 |
| --- | --- | --- |
| Conversation，会话 | 把用户的一系列交流放在一起 | 超级智能体请求尚无稳定 conversationId |
| Run，单次执行 | 一次提交产生的步骤、结果和结束原因 | 每次创建新的 ZxlManus，状态只能运行一次 |
| 历史消息 | 用户可查看和继续使用的交流记录 | 页面 messages 当前主要保存在内存 |
| 模型上下文 | 每次请求实际提供给模型的消息 | 单次任务使用 messageList，跨任务尚未恢复 |

一个会话可以包含多个 Run。**恢复会话时仍创建新的 Agent 实例，不能把已 FINISHED 的对象改回 IDLE 再运行。** 预算、状态和工具调用响应属于本次 Run，历史消息通过仓储加载。

历史完整存储与模型上下文窗口也不同：页面可以保留全部历史，模型按本次需要读取一个完整消息组组成的窗口。模型能够“记住”，来自应用重新提供了历史。

## 2. 从当前代码定位问题

下面保留实施前的问题背景；AiController、BaseAgent 与 ToolCallAgent 已通过会话服务、独立完成监听和 Prompt 临时指令调整。旧 LoveApp 的 Kryo 实现保留作为对照。

- [AiController](../../src/main/java/com/zxl/zxlaiagent/controller/AiController.java)：超级智能体接口只有 message，每次 new ZxlManus。
- [BaseAgent](../../src/main/java/com/zxl/zxlaiagent/agent/BaseAgent.java)：messageList 保存本次上下文，startRun 会追加当前用户消息。
- [ToolCallAgent](../../src/main/java/com/zxl/zxlaiagent/agent/ToolCallAgent.java)：助手工具调用与工具响应通过 conversationHistory 回填。
- [FileChatBasedMemory](../../src/main/java/com/zxl/zxlaiagent/chatmemory/FileChatBasedMemory.java)：恋爱助手已有 Kryo 文件记忆；静态 Kryo 的并发访问、失败吞掉和记录恢复边界需要识别。
- 旧 LoveMaster 页面曾在 onMounted 生成新的 chatId；该独立页面已移除，旧路径重定向到统一页面。
- [AgentChat](../../zxl-Ai-agent-fronted/src/views/AgentChat.vue)：现在统一提供 Agent 类型选择、会话列表和恢复。

本课先给超级智能体建立明确的会话仓储和服务边界。已有恋爱助手作为对照，逐步统一；先完成一个入口的完整链路。

## 3. 任务一：定义会话与消息模型

建议新增 `conversation` 包，包含 model、repository、service 和 controller。起步仍放在同一 Spring Boot 应用中。

需要的字段：

| 对象 | 必需字段 |
| --- | --- |
| Conversation | id、agentType、title、createdAt、updatedAt、version、messages、runs |
| ConversationSummary | id、agentType、title、updatedAt、messageCount、busy |
| MessageRecord | messageId、runId、role、content、toolCalls、toolCallId、toolName、createdAt |
| ToolCallRecord | id、name、arguments |
| RunRecord | runId、startedAt、finishedAt、endReason、status；实现补充 answer、error、events 用于页面恢复 |

`role` 本课限定为 user、assistant、tool。普通助手回答没有 toolCalls；助手调用工具时保存调用列表，每条 tool 消息保存对应的 toolCallId。时间使用 Instant，ID 由服务端生成 UUID。

不要直接把 Spring AI 的抽象 Message 类型写入 JSON 后期望自动恢复：明确的 DTO 能让文件格式、框架消息转换和前端返回格式各自独立。

## 4. 任务二：建立仓储边界并实现 JSON 文件存储

先实现下面接口，再编写 `FileConversationRepository`：

```java
public interface ConversationRepository {
    Conversation create(String title);
    Optional<Conversation> find(UUID id);
    List<ConversationSummary> list();
    void save(Conversation conversation);
    void delete(UUID id);
}
```

约束：

1. 每个会话一个 JSON 文件，目录可配置，例如 `agent.conversation.storage-dir`；默认使用项目运行数据目录，不提交聊天内容。
2. 文件名来自已解析的 UUID，不能直接拼接客户端提交的任意路径。
3. 使用注入的 ObjectMapper，明确处理 Instant；写临时文件后原子替换，避免进程中断留下半个文件。
4. 会话不存在、文件损坏、读写失败分别给出明确异常；不能把读取失败当作一个新的空会话。
5. 同一会话的读改写必须有一致性规则，不能两个请求同时覆盖旧快照。

本课使用单进程文件仓储，便于观察持久化过程。第九课再把仓储实现迁移到数据库，并处理多实例竞争。

## 5. 任务三：实现会话管理服务与接口

建议新增 `ConversationService`，统一校验标题和 ID、调用仓储、组装返回 DTO。Controller 负责 HTTP 参数与状态码。

| HTTP 接口 | 用途 | 预期 |
| --- | --- | --- |
| `GET /api/agent-types` | 获取可用 Agent 类型 | 返回注册表中的中英文目录 |
| `POST /api/conversations` | 创建会话，接收 title、agentType | 返回服务端生成的 ID 与固定类型 |
| `GET /api/conversations` | 列出会话摘要 | 按更新时间倒序 |
| `GET /api/conversations/{id}` | 恢复会话详情 | 不存在时 404 |
| `DELETE /api/conversations/{id}` | 删除明确指定的演示会话 | 同时清除对应历史 |

统一请求使用 `POST /api/ai/agent/chat`，接收 message、conversationId；兼容保留 `/api/ai/manus/chat`。旧调用未传 ID 时由服务端新建默认工具会话，并把实际 ID 告知前端，不能前端显示一个 ID、后端实际另存一个。

建议用最初的命名 SSE 事件携带 conversationId 与 runId，前端持久保存当前会话 ID。前端相关类型和请求由助手衔接。

## 6. 任务四：恢复模型上下文并保存新结果

新增 `AgentConversationService`，让请求按以下顺序执行：

```text
查找会话
→ 获得该会话的执行权
→ 创建新的 runId，由类型 Provider 创建新的 Agent
→ 将已保存记录转换成 Spring AI 消息
→ 设置预算、工具和流式适配器
→ runStream 追加本次用户消息并执行
→ 终态时保存本次消息与结束原因，再向页面发送终态
→ 释放该会话执行权
```

要点：

- 当前用户消息由 startRun 追加，服务层不要重复追加。
- 系统提示词来自服务端当前配置；内部下一步提示要与真正用户消息区分，不应不断混入用户历史。
- 保留 assistant tool_calls 与对应的 tool 响应，原始调用 ID 必须一致。不能只存最终回答而丢掉工具证据。
- 模型返回的思考文字与用户消息不同，本课不把它作为必须恢复的模型上下文。
- 在明确的终态回调中保存一次快照，持久化失败要返回明确状态；不能仅依靠浏览器关闭事件保存。
- runId 用于去重，完成回调重复到达不能重复追加历史。
- 执行失败时保留已确认的完整消息组和运行状态；未完成的工具调用组不能作为完整历史送给下一次模型请求。

可以给 BaseAgent 增加独立的运行完成监听器或在执行服务中包装生命周期。它与 SSE 输出监听器是两种职责，不要用设置第二个 listener 覆盖页面事件。

## 7. 任务五：处理上下文窗口与并发边界

### 上下文窗口

先提供按最近若干个完整交流组裁剪的策略。一个组包括用户消息、相关助手工具调用、对应工具结果和后续回答。

如果一条 tool 消息保留下来，而它对应的 assistant tool_calls 被裁掉，恢复的上下文会失配。因此不要对扁平消息列表直接截取最后 N 条。完整记录仍保存在仓储，发送给模型的只是选定窗口。

Token 预算、摘要和长期事实抽取在第十一与十四课扩展，本课先把消息组边界做正确。

### 并发

- 同一会话同时只运行一个任务；第二个请求返回明确的 `CONVERSATION_BUSY`，不能静默排队并追加重复用户消息。
- 不同会话可以同时执行，不能把全应用所有会话放在同一把长锁中。
- 删除会话时如有运行中的任务，先拒绝并说明；任务取消与删除联动以后再扩展。
- 页面停止接收后，服务端仍按实际执行状态决定存储什么，不能直接把工具副作用标成已取消。

本课为单用户本地演示。身份与租户授权在第十七课接入，不能把 UUID 不易猜测当作授权机制。

## 8. 任务六：集中验收并记录证据

Java 实现完成后统一反馈，前端由助手接入会话列表、新建、切换和恢复。

| 场景 | 必须看到的结果 |
| --- | --- |
| 会话 A 告知一个演示昵称，随后提问 | 同会话能使用该历史 |
| 刷新页面后继续 A | ID 一致、历史可恢复 |
| 重启后端后继续 A | 从文件恢复，不依赖旧 Agent 实例 |
| 新建会话 B 提问 A 的昵称 | B 中没有 A 的私有上下文 |
| A 查询时间后再次交流 | 工具调用与结果的 ID 仍可正确配对 |
| 上下文超过窗口 | 旧历史仍可查看，发送给模型的消息组完整 |
| A 同时提交两次 | 一个执行，另一个明确返回忙碌 |
| 会话文件损坏或目录不可写 | 显示持久化故障，不假装空会话或保存成功 |

文件、并发和消息转换优先用本地用例验证；真实模型只验证必要的记忆与工具连续对话。使用合成演示内容，运行数据不提交到 Git。

## 9. 面试复盘与提交方式

完成后能说明：

1. conversationId 和 runId 为什么要分开？
2. 历史记录存在磁盘里，为什么模型仍可能“忘记”？
3. 为什么不能复用一个 FINISHED 的 Agent 实例继续聊？
4. 为什么工具消息不能按条数随意截断？
5. 文件持久化为什么需要原子替换与并发控制？
6. 为什么有了长期会话，也不代表实现了长期记忆与租户授权？

按职责细分 Git 提交：会话模型与仓储、管理接口、Agent 上下文恢复、并发与窗口、前端衔接、验收文档。每批提交说明改动与验证结果；完成后统一推送。

工程已完成后，建议沿[实现记录中的代码地图](04-implementation.md)读一次调用链，再独立新增一种简单角色类型，复用现有会话与页面。第五课继续进入 Plan / Task 校验与依赖数据流。
