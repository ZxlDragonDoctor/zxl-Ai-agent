# 第四课：会话持久化实施计划

目标：按已发布的第四课六项任务，实现超级智能体跨请求、刷新和服务重启后的会话恢复，并提交、推送。

方案依据：[第四课](../../learning/04-conversations-and-memory.md)。用户已要求直接实现整课，沿用该设计，不重复征询范围。

## 数据与执行边界

2026-10-19 用户补充：恋爱助手属于 Agent 类型，不再单独维护一个页面。统一对话入口，通过 Spring Provider 注册表提供通用工具助手与恋爱助手；会话固定 agentType，旧前端入口重定向。旧 LoveApp 接口与 Kryo 数据保持兼容，不宣称已迁移到新会话。

- `conversation/model`：显式 JSON DTO，消息、工具调用和 Run 分开记录，不序列化 Spring AI 抽象消息。
- `conversation/repository`：每会话一个 UUID 文件，临时文件原子替换，版本比较防止旧快照覆盖；损坏、缺失与 I/O 错误分开返回。
- `conversation/service`：管理服务、按会话的执行权、完整消息组窗口、Spring AI 转换和 Manus 执行服务。
- `BaseAgent`：独立完成监听器在发送终态前持久化；断开输出仍等待工作线程收尾并释放执行权。内部下一步提示只用于本轮 Prompt。
- Run 开始先记录用户消息与 running 状态，完成时按 runId 替换一次结果。服务重启后遗留 running 标为 interrupted，不自动重放工具。
- SSE 首个 RUN_STARTED 携带 conversationId/runId；历史保存工具事件和回答，不把思考增量当作模型恢复必需数据。
- 同一会话只允许一个任务；删除也必须获得执行权。不同会话无需等待另一会话的模型响应。
- 前端保留当前会话 ID，提供列表、新建、切换、恢复和明确的删除确认；保留第三课的流式执行卡片与中英文。

## 实施批次与提交

- [ ] 1. 会话模型与仓储：新增 `conversation/model/*.java`、`repository/*.java`、异常类型；忽略 `/runtime-data/`，构建后提交。
- [ ] 2. 管理接口与执行权：新增 `ConversationService`、`ConversationRunGuard`、`ConversationController`、限定范围的异常处理器；明确 400/404/409/500，构建后提交。
- [ ] 3. Agent 生命周期：修改 `BaseAgent`、`ToolCallAgent` 与事件类型；完成监听独立于页面事件，保存失败明确标记，构建后提交。
- [ ] 4. 上下文恢复与执行服务：新增消息转换、窗口策略、`ManusConversationService`；修改 `AiController` 委托执行，构建后提交。
- [ ] 5. 前端会话体验：新增 API、类型、侧栏与恢复逻辑；将现有事件归并提取成可复用函数，补齐中英文和错误提示，生产构建后提交。
- [ ] 6. 更新第四课、实现记录、课程地图和 README；记录构建结果与尚未验证的边界，按实际结果发布截图。
- [ ] 7. 检查本次文件、细分提交、推送并核对远程 master。

## 构建与完成依据

本轮用户要求实现与推送；不新增或运行自动测试。后端使用 `mvn -q -DskipTests compile`，前端使用 `npm run build` 检查编译和类型。页面观察记录与自动测试结果分开，不将编译通过声称为全部验收通过。

配置默认：`agent.conversation.storage-dir=runtime-data/conversations`、`agent.conversation.context-runs=8`。单进程、单用户演示；文件存储不提供多实例锁与用户授权；停止接收不保证撤销工具副作用。
