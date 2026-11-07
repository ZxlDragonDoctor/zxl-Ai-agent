# 第十七课：正式多 Agent 编排入口

## 从接口理解责任边界

这批将可信执行单元连到 `/multi-agent`。浏览器携带安全会话和 CSRF，Controller 只接受 goal、context、timeoutMs；ActorRef 由已验证请求提供，模型和业务 JSON 没有身份选择权。

| 正式接口（均在 /api 后） | 行为 |
| --- | --- |
| POST /ai/multiagent/runs | 创建当前账号的运行，返回202与executionId |
| GET /ai/multiagent/runs/{id} | 当前 owner + agent.execute/knowledge.read/run.readOwn，返回本运行报告及私有事件 |
| POST /ai/multiagent/runs/{id}/cancel | 当前 run.writeOwn；写门只短暂执行control.cancel，回包只有ID/确认状态 |
| GET /ai/multiagent/status | 有界当前候选与PG owner交集，不返回全局observer或其他用户运行 |

其他用户、其他租户与不存在的ID统一404。旧全局agents/events/harness/直接消息入口继续关闭，旧Controller仅在legacy-demo配置注册。旧同步/流式编排接口未在正式模式开放；本页面当前通过轮询显示阶段，持久事件重放与SSE属于后续课程。

## 编排器内部

`submitScoped` 校验输入后，以服务器新UUID调用可信 Factory；owner登记与上下文准备成功，才写registry、queued报告和实际dispatch。运行边界固定包含agent.execute、knowledge.read、run.readOwn。任务claim和模型/成功提交都遵循“权限门 → 短控制准入”，远程等待在门外。

正式startWork不再用control.admit包住整个executeTask，避免控制锁→PG与模型PG→控制锁形成反序。pendingTasks使用runId与Task对象身份，activePlans按runId，模型重复的task/plan ID不能覆盖另一运行。

WorkflowRun在实际发布gate内构造并保存敏感latest/outcome，私有队列只做本地入队；授权拒绝不被“观察者断线”catch吞掉。撤权后用不含goal/result/tasks的中性终态完成Future、回收排队工作；已开始的实际调用租约仍等真实Future结束才能释放。取消门也不会包住后续报告的第二个PG事务。

审查又复现“回调已完成但外层commit失败”的挂起：运行报告/终态及其事件现在在门内准备，事务成功返回才释放可见性；失败丢弃pending并中性收尾。见[提交与发布边界](17-run-publication-commit.md)。这不是全部工具/模型事件与PG的分布式事务。

## 前端与验证边界

正式页面使用sessionFetch发送同源Cookie/CSRF；身份generation变化清空私有视图并停止轮询。开始取消时推进本地请求版本，旧poll不能覆盖新取消状态。计划JSON与任务输出默认折叠，最终业务回答直接显示。原旧页面及其未提交改动保留在legacy分支，本批没有覆盖它们。

自动化包含可信编排、任务实际开始、当前owner登记、Controller契约与相关回归；真实PG工作角色测试和真实HTTP检查单独记录。正式服务已重新启动并通过10项实际HTTP检查：本人可读、他人读/取消404、他人列表不含本运行、全局入口关闭、身份字段注入400和错误CSRF403。[HTTP记录](evidence/17-orchestration-live-http-2026-11-07.json)。HTTP使用独立Cookie会话，不等于两个独立浏览器profile。

真实页面首轮已实际规划并请求knowledge__knowledge_lookup_v2，工具返回MCP_DEADLINE_EXCEEDED，运行正确失败，没有伪造答案。错误截图及安全工具事件会保留；后续混合检索预算调查不能反向证明这是该次超时的唯一根因。整课保持进行中，旧历史受控导入、部分业务页面与独立浏览器profile等原有缺口不能因开放编排接口自动算通过。

混合检索阶段预算修复后，仅重启知识worker，保留原问题和90秒运行预算复验：规划1次、研究2次真实模型调用，MCP v2成功；真实HTTP text-embedding-v3 1024D，PG向量和关键词路线均OK、无降级，工具内部1452ms。最终回答只包含甲资料与原文行号，reranker为disabled。一次成功不代表稳定性。取消后自动轮询至实际资源清理完成、中英文切换、历史选择/刷新、退出清空和换乙无甲记录均有[截图证据](../images/lesson17-orchestration-2026-11-07/README.md)。

当前前端24项、图谱37项及生产构建通过（既有3D大分包警告仍存在）。课程新增正式入口、归属登记、混合预算三个证据节点；筛选/关联/2D一跳二跳/3D阅读/双语已操作。个人进度先导出，完整恢复后再次导出JSON深比较相等。
