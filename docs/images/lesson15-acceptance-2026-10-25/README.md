# 第十五课页面逐状态验收

日期：2026-10-25。真实本机服务；截图是操作当时的状态，失败、加载及后续重跑均保留。自动化和真实模型证据分别见本目录 JSON，不能由截图推导全量质量保证。

## 操作、预期与实际

| 步骤/操作 | 预期 | 实际观察 | 截图 |
| --- | --- | --- | --- |
| 01-research-provider | 统一页面显示MCP类型 | 选择研究类型并新建：统一会话显示MCP工具状态，原3个会话保留。 | [查看](01-research-provider.jpg) |
| 02-discovery-ready | 发现固定只读工具 | 刷新工具发现：READY，协议2024-11-05，唯一允许工具knowledge__knowledge_lookup_v1。 | [查看](02-discovery-ready.jpg) |
| 03-tool-schema | 展开固定schema | 展开工具契约：原名、稳定别名、v1、SHA-256及输入schema可见。 | [查看](03-tool-schema.jpg) |
| 04-keyword-success | 关键词返回当前证据 | keyword实际MCP→PG：返回VPN-401凭据过期资料及行号。 | [查看](04-keyword-success.jpg) |
| 05-source-current | 打开原文进入加载 | 点击查看原文后的加载中，按钮暂禁用；完成态见06。 | [查看](05-source-current.jpg) |
| 06-source-verified | 显示已核对原文 | 原文复核完成后显示当前revision与UTF-16范围；已滚动到原文。05为加载中截图。 | [查看](06-source-verified.jpg) |
| 07-source-closed | 关闭原文 | 关闭原文：原文区域消失，保留检索结果。 | [查看](07-source-closed.jpg) |
| 08-source-reopened | 重新打开原文 | 再次查看原文成功，重新请求当前资料。 | [查看](08-source-reopened.jpg) |
| 09-retrieval-trace | 展开检索轨迹 | 展开检索记录：实际trace、耗时与检索阶段。 | [查看](09-retrieval-trace.jpg) |
| 10-empty-success | 空检索明确无结果 | 预期空成功，但实际出现MCP_PROTOCOL_FAILURE；已定位SSE默认30秒闲置超时，保留失败证据，后续重跑。 | [查看](10-empty-success.jpg) |
| 11-invalid-query | 非法输入不调用 | 空查询被服务端拒绝为MCP_INVALID_ARGUMENT；截图10实际为闲置SSE过期故障，待修复重跑。 | [查看](11-invalid-query.jpg) |
| 12-empty-success-rerun | 空检索恢复可用 | SSE闲置修复后重跑不存在关键词：预期OK空hits。 | [查看](12-empty-success-rerun.jpg) |
| 13-real-vector-query | 真实向量召回 | vector实际调用当前1024维Embedding并查询PG，返回当前资料。 | [查看](13-real-vector-query.jpg) |
| 14-stop-lookup | 停止本地等待 | 停止接收：本地取消收取，明确不承诺远端回滚；结果未迟到覆盖。 | [查看](14-stop-lookup.jpg) |
| 15-hybrid-after-cancel | 停止后可再查询 | 停止接收后立即再查：hybrid返回当前PG资料，未被旧取消请求覆盖。 | [查看](15-hybrid-after-cancel.jpg) |
| 16-deadline | 短预算明确超时 | 100毫秒共享预算：明确MCP_DEADLINE_EXCEEDED，不渲染成空知识成功。 | [查看](16-deadline.jpg) |
| 17-real-research-run | 真实模型选择工具 | 真实模型研究请求执行状态；后续完成态与工具卡另存。 | [查看](17-real-research-run.jpg) |
| 18-real-tool-parameters | 显示真实工具参数 | 实际模型调用参数与PG结果；本次只召回标题，模型拒绝给出没有证据的处理结论，需修复检索查询表达。 | [查看](18-real-tool-parameters.jpg) |
| 19-fixture-lookup | 查到合成资料 | 查询独立合成资料L15REF8654，供换版/删除验收，未改用户vpn-guide。 | [查看](19-fixture-lookup.jpg) |
| 20-stale-source-rejected | 换版后拒绝旧证据 | 合成资料v2查询后已更新v3：旧引用明确拒绝，未显示为当前原文。 | [查看](20-stale-source-rejected.jpg) |
| 21-current-revision-rerun | 重查新版本 | 重新检索得到合成资料v3，内容与版本均更新。 | [查看](21-current-revision-rerun.jpg) |
| 22-current-source-v3 | 新版本原文一致 | 当前v3原文、revision和UTF-16范围可见。 | [查看](22-current-source-v3.jpg) |
| 23-deleted-source-rejected | 删除后拒绝来源 | 合成资料软删除v4：旧引用拒绝，原文不再作为当前有效资料。 | [查看](23-deleted-source-rejected.jpg) |
| 24-service-unavailable | 服务离线明确状态 | 停止真实MCP进程后发现刷新：UNAVAILABLE及明确诊断，无可用工具，主应用会话仍可读。 | [查看](24-service-unavailable.jpg) |
| 25-offline-call-failure | 离线调用失败 | 离线状态执行真实查询：预算耗尽/服务失败，未展示为空查询成功。 | [查看](25-offline-call-failure.jpg) |
| 26-discovery-restored | 恢复后发现工具 | 真实MCP进程恢复后刷新：READY，工具身份与schema不变。 | [查看](26-discovery-restored.jpg) |
| 27-restored-query-success | 恢复后可查询 | 服务重启后实际keyword查询401成功，数据仍来自原PG资料。 | [查看](27-restored-query-success.jpg) |
| 28-real-pg-outage | PG离线明确失败 | 真实PG进程停止后MCP仍在线，但查询明确MCP_TOOL_ERROR，未返回内存资料；需补展示安全的业务诊断码。 | [查看](28-real-pg-outage.jpg) |
| 29-pg-failure-diagnosis-rerun | 显示安全业务诊断 | PG实际离线复测：协议失败优先，同时显示安全业务诊断MCP_REMOTE_BUSINESS_FAILURE，不显示虚假的检索记录或资料。 | [查看](29-pg-failure-diagnosis-rerun.jpg) |
| 30-pg-restarted-query | PG重启后可查询 | 真实PG进程重启后查询恢复；原vpn-guide仍为v2，来源未切到内存。 | [查看](30-pg-restarted-query.jpg) |
| 31-real-research-supported | 混合检索保留错误码 | 同题复测：真实模型选择MCP工具，当前PG原文支持VPN-401凭据过期、统一身份平台重新登录及网络/客户端检查。耗时21.5秒，结论人工逐项核对。 | [查看](31-real-research-supported.jpg) |
| 32-real-final-answer | 回答与原文逐条一致 | 折叠工具细节后显示最终三条有原文支持的结论及来源行号。 | [查看](32-real-final-answer.jpg) |
| 33-stop-research | 停止接收转入保存 | 立即停止接收后，页面提示等待服务端保存，暂不允许重复发送；不等于远端模型立即取消。 | [查看](33-stop-research.jpg) |
| 34-stop-persisted | 最终历史记为中断 | 7秒后服务端保存OUTPUT_INTERRUPTED，输入恢复可用；未产生可发布的研究回答。 | [查看](34-stop-persisted.jpg) |
| 35-real-no-evidence | 无证据不继续生成 | 真实模型仅选择keyword工具查询不存在的词；成功空结果后执行器明确无当前资料，不再启动生成请求。 | [查看](35-real-no-evidence.jpg) |
| 36-history-restored | 刷新恢复公开历史 | 刷新历史后恢复4个本课run与工具状态，当前空证据回答仍可见。 | [查看](36-history-restored.jpg) |
| 37-english-tools | 切换英文界面 | 英文切换：Provider、发现、协议实验、范围说明与按钮均为英文，动态原文保留。 | [查看](37-english-tools.jpg) |
| 38-english-lab-expanded | 英文协议实验可操作 | 英文协议实验实际展开：Query、Retrieval mode、Budget、Run read-only lookup及合法范围均为英文。 | [查看](38-english-lab-expanded.jpg) |
| 39-agent-type-isolation | 旧Agent不展示MCP面板 | 切换既有恋爱助手会话：恢复其历史，MCP专用工具面板不出现，未新建独立页面。 | [查看](39-agent-type-isolation.jpg) |
| 40-mobile-390 | 390像素无页面横溢 | 目标tab实际390px视口，检查横向溢出与对话/工具面板布局。 | [查看](40-mobile-390.jpg) |
| 41-mobile-tools | 移动工具面板可读 | 390px工具面板展开，无页面横向溢出；控件和发现状态可读。 | [查看](41-mobile-tools.jpg) |
| 42-mobile-lab | 移动协议实验可用 | 390px协议实验展开，字段按移动布局排列，面板滚动保留对话输入。 | [查看](42-mobile-lab.jpg) |
| 43-learning-lesson15 | 课程内容与证据更新 | 第十五课阅读卡显示工程已实现与241项分层证据；109节点154关系，个人仍0/28。 | [查看](43-learning-lesson15.jpg) |
| 44-learning-one-hop | 只展示一跳邻居 | 第十五课1跳邻域：保留该课及直接相连知识/证据节点与真实连线。 | [查看](44-learning-one-hop.jpg) |
| 45-learning-2d | 切换2D保留选择 | 同一1跳关系切换2D，知识窗口与关联仍可见。 | [查看](45-learning-2d.jpg) |
| 46-mcp-ownership-knowledge | 点击目标概念切换阅读 | 尝试点击SVG概念节点，但浮窗遮挡导致未选中，画面仍为原课程；后续用键盘入口验证。 | [查看](46-mcp-ownership-knowledge.jpg) |
| 47-mcp-concept-keyboard | 键盘选中目标概念 | 键盘选择新增概念成功，连接代际的双语知识正文与源码证据可读。 | [查看](47-mcp-concept-keyboard.jpg) |
| 48-lesson16-next | 关联下一课完整任务 | 沿先修关系进入第十六课：完整六单元任务可读，工程状态仍计划中，未自动标为掌握。 | [查看](48-lesson16-next.jpg) |
| 49-mcp-filter | 按概念筛选MCP | 概念类型+MCP检索显示协议边界与新连接代际知识；筛选不改变个人掌握。 | [查看](49-mcp-filter.jpg) |
| 50-evidence-relations | 按文档关系筛选 | 关系筛选为课程记录，核对源码/文档证据关系可见。 | [查看](50-evidence-relations.jpg) |
| 51-lesson16-english | 英文课程与3D一致 | 第十六课英文阅读卡与3D邻域，任务包/实践/边界均双语，个人0/28。 | [查看](51-lesson16-english.jpg) |
| 52-full-constellation | 全图显示完整网络 | 恢复全图/全景：109节点154关系仍显示，星点和连线完整，个人掌握0/28。 | [查看](52-full-constellation.jpg) |
| 53-progress-restored | 恢复个人进度与备份一致 | 合并导入后仍保留两个验收新增的阅读记录，导出对比未完全一致；此失败促使新增独立的完整备份恢复入口，见54重跑。 | [查看](53-progress-restored.jpg) |
| 54-exact-progress-restored | 完整恢复后导出完全一致 | 点击恢复完整备份并选验收前JSON，再导出逐值比较完全一致（12条原记录）；保留原有笔记、掌握状态和阅读位置，移除2条验收临时记录。 | [查看](54-exact-progress-restored.jpg) |
| 55-original-history-restored | 保留原三会话 | 确认测试会话不忙后，将其JSON可恢复地移到target备份；刷新页面仅剩原有3个演示会话，选回通用工具助手与原始星河会话，未改写原会话内容。 | [查看](55-original-history-restored.jpg) |

## 数据恢复与边界

- 仅使用合成验收资料；该资料已软删除，原 VPN 文档保持原版本。
- 个人进度先导出；第53步发现合并导入无法移除测试阅读记录，新增完整备份恢复后，第54步再次导出与验收前 JSON 深度比较完全一致。备份仅在忽略的 target 保存，不发布个人笔记。
- 测试会话在 busy=false 后可恢复地移入 target 备份。第55步确认仅保留原3个会话，原有历史不改写。
- 停止接收不代表远程停止计费或回滚；第33步仍等待保存，第34步才确认终态。第05步为加载中，第06步才为当前原文。
- 第10步暴露闲置连接问题，后续修复、真实传输测试及第12步重跑。第46步点击落在浮动阅读卡上，未切换节点，第47步用键盘确认选中目标。
- 图谱全量109节点154关系；筛选、1/2跳、2D/3D、阅读卡、关系导航、双语与个人进度分别操作，掌握状态没有自动变更。
- [打开连续图库](gallery.html)。
