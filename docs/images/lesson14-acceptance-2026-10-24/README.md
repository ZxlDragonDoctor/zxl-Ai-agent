# 第十四课逐操作截图验收

执行日期：2026-10-24 至 2026-10-25（Asia/Shanghai），目录保留本批起始日期。前端5174、统一会话8123、独立知识worker18132、真实PG15432。截图为实际操作后的视口；[连续画廊](gallery.html)与下表保留失败、处理中和复测结果，不把所有图片都算通过。

## 已取得的证据

- 后端33套186项、本课7套48项均0失败/错误/跳过；前端19项知识库/事件与31项图谱共50项通过，生产构建成功。既有3D大分块提示保留。
- 事实管理：提议/未确认不召回/拒绝/再提议/确认/重复确认/更正取消与提交/旧值退出/两页409竞争/刷新/固定Bob隔离/5秒TTL/删除过期记录/跨会话/删除后连续查询。
- 回答：原统一页面Provider、真实生成与支持性、缺失子问题、澄清、资料不足、来源打开关闭与跨请求E1映射、刷新恢复、删除资料后来源失效、真实模式503失败卡与重试恢复。未知引用、换版、ABA、排队取消/删除等由专项测试验证，不冒充全部在页面触发。
- 日期1970与模式切换残留两项在真实页面发现，先记录失败再修复，07/43复测。44是真实外部生成/检查失败，45重试恢复，不替换失败截图。54/55实际是桌面图，不计移动通过；56才是390宽、scrollWidth390的移动验收。
- 图谱101节点141关系28课，筛选/关系/搜索、1跳/2跳/全图、2D/3D、6个相关证据/概念节点、第十四/十五课阅读与中英文实际查看。个人0/28未自动改变，未编辑笔记。

## 原始报告与质量边界

- [主支持性评判v1](semantic-primary-v1.json)：12例10正确，不支持结论误接受1，预设门槛**未通过**。
- [额外重复运行v1](semantic-repeat-v1.json)：另外12次同配置judge调用；独立保留，不能挑较好结果替代主报告或合并成独立样本。两次共24次judge调用。
- [真实完整回答v2](pipeline-v2.json)：5个合成开发案例，4次生成/3次judge；2个可答、1个澄清、1个无资料、1个部分回答状态符合预期。3条最终结论逐条与来源核对，引用可解析3/3、支持3/3、需引用结论覆盖3/3；应拒答组错误回答0/1，可答组不必要拒答0/2。小样本与已知开发案例不是未调优留出集，也不是生产质量证明。
- [实际进程重启](process-restart.json)：worker25904→45452，PG中已确认en/v4召回保持一致。不是只new Service对象，也不是数据库进程或灾备演练。

v2已知覆盖指令隔离有PG对抗回归，不能由此宣称通用提示注入防御或原始judge门槛已通过。页面另有真实调用，未将其未计量调用数混入上述固定报告。

## 临时数据与操作范围

仅导入本批公开合成809手册，完成后软删除；原`vpn-guide`仍为401手册、version2。Alice/Bob活动记忆恢复初始0，软删审计保留。两条本批会话可恢复归档至本地忽略目录`target/lesson14-session-backup`，原三条会话及初始选择保留；未永久删除原历史。没有改个人学习状态、笔记或上传本地配置。

## 截图索引

“操作与预期/实际”列记录本次可见结果；未逐一组合所有选项，不承诺任意操作零缺陷。

| 截图 | 操作与预期 / 实际结果 | 状态 | 截图时间（+08:00） |
| --- | --- | --- | --- |
| [01-agent-type](01-agent-type.jpg) | 统一对话类型选择企业知识助手，既有三会话保留 | 已核对 | 2026-10-24T23:58:44+08:00 |
| [02-new-knowledge-session](02-new-knowledge-session.jpg) | 新建知识会话，同一聊天页面展示记忆入口 | 已核对 | 2026-10-24T23:58:58+08:00 |
| [03-memory-open](03-memory-open.jpg) | 展开PG事实管理，Alice初始0条；默认一小时需人工确认 | 已核对 | 2026-10-24T23:59:47+08:00 |
| [04-pending-proposal](04-pending-proposal.jpg) | 提交提议后的处理中状态；不是确认完成证明，最终PENDING见07。 | 进行中 | 2026-10-25T00:00:00+08:00 |
| [05-pending-not-recalled](05-pending-not-recalled.jpg) | 待确认时实际召回为0 | 已核对 | 2026-10-25T00:00:00+08:00 |
| [06-date-contract-bug](06-date-contract-bug.jpg) | 发现新记录时间被数字序列化，UI误判1970年过期；此为修复前截图，不记通过 | 修复前问题 | 2026-10-25T00:01:19+08:00 |
| [07-iso-pending-fixed](07-iso-pending-fixed.jpg) | 重启后ISO日期正确，PENDING确认按钮可用 | 已核对 | 2026-10-25T00:07:14+08:00 |
| [08-reject-proposal](08-reject-proposal.jpg) | 拒绝提议后REJECTED v2，不自动保存 | 已核对 | 2026-10-25T00:07:55+08:00 |
| [09-repropose](09-repropose.jpg) | 新用户消息重新提议，保留旧拒绝记录 | 已核对 | 2026-10-25T00:07:56+08:00 |
| [10-confirm](10-confirm.jpg) | 人工确认后CONFIRMED v2，有效记忆1条 | 已核对 | 2026-10-25T00:07:57+08:00 |
| [11-confirm-again-recall](11-confirm-again-recall.jpg) | 重复确认仍v2，实际召回中文偏好1条 | 已核对 | 2026-10-25T00:07:59+08:00 |
| [12-bob-isolated](12-bob-isolated.jpg) | 切换限定Bob演示身份，无法召回Alice中文偏好，0条 | 已核对 | 2026-10-25T00:08:15+08:00 |
| [13-correction-open](13-correction-open.jpg) | 打开更正输入，提交前事实未改变 | 已核对 | 2026-10-25T00:08:15+08:00 |
| [14-correction-cancel](14-correction-cancel.jpg) | 取消尚未提交的更正，保持原确认值 | 已核对 | 2026-10-25T00:08:16+08:00 |
| [15-correction-pending](15-correction-pending.jpg) | 更正为英文后PENDING v3，旧中文立即退出召回，实际0条 | 已核对 | 2026-10-25T00:08:20+08:00 |
| [16-correction-confirmed](16-correction-confirmed.jpg) | 确认英文更正后v4，仅召回en，不再召回旧zh | 已核对 | 2026-10-25T00:08:36+08:00 |
| [17-refresh-current](17-refresh-current.jpg) | 刷新从真实PG读取当前v4 | 已核对 | 2026-10-25T00:08:36+08:00 |
| [18-process-restart-recall](18-process-restart-recall.jpg) | 真实worker进程25904→45452后仍召回en v4；后台before/after完全一致 | 已核对 | 2026-10-25T00:11:48+08:00 |
| [19-answer-running](19-answer-running.jpg) | 统一会话发送有依据VPN问题，真实模型执行中 | 进行中 | 2026-10-25T00:11:49+08:00 |
| [20-synthetic-source-import](20-synthetic-source-import.jpg) | 通过资料页面导入独立合成VPN809文档，原vpn-guide保持401内容不改 | 已核对 | 2026-10-25T00:15:15+08:00 |
| [21-real-supported-claims](21-real-supported-claims.jpg) | 导入有效809资料后真实生成与逐结论检查返回PARTIAL，有4条支持结论，其余已移除 | 已核对 | 2026-10-25T00:16:53+08:00 |
| [22-citation-source-open](22-citation-source-open.jpg) | 点击E1后从当前PG重核generation/revision/span，展示准确手册原文 | 已核对 | 2026-10-25T00:16:54+08:00 |
| [23-citation-close](23-citation-close.jpg) | 关闭原文后保留本次结论 | 已核对 | 2026-10-25T00:16:55+08:00 |
| [24-citation-reopen](24-citation-reopen.jpg) | 点击另一条结论的同E1，可重新打开相同快照正确原文 | 已核对 | 2026-10-25T00:16:57+08:00 |
| [25-correction-current-version](25-correction-current-version.jpg) | 第一页更正至待确认v5 | 已核对 | 2026-10-25T00:21:13+08:00 |
| [26-stale-version-conflict](26-stale-version-conflict.jpg) | 第二页旧v4确认遭409版本冲突，并刷新显示当前v5，无条件覆盖被阻止 | 已核对 | 2026-10-25T00:21:15+08:00 |
| [27-confirm-current-version](27-confirm-current-version.jpg) | 当前页面确认中文v6，冲突旧请求未覆盖 | 已核对 | 2026-10-25T00:21:17+08:00 |
| [28-five-second-confirmed](28-five-second-confirmed.jpg) | 真实PG确认5秒简洁偏好，当前有效2条 | 已核对 | 2026-10-25T00:21:41+08:00 |
| [29-reject-permission-assertion](29-reject-permission-assertion.jpg) | 权限断言不在白名单，400明确拒绝，不保存为事实 | 已核对 | 2026-10-25T00:21:42+08:00 |
| [30-expired-excluded](30-expired-excluded.jpg) | 5秒事实到期后立即不再召回，仅中文v6仍有效 | 已核对 | 2026-10-25T00:22:03+08:00 |
| [31-delete-expired](31-delete-expired.jpg) | 到期记录可正常删除，修复旧409问题 | 已核对 | 2026-10-25T00:22:05+08:00 |
| [32-one-day-option](32-one-day-option.jpg) | 选择1天有效期后尚未提交，不创建额外事实 | 已核对 | 2026-10-25T00:22:06+08:00 |
| [33-one-hour-option](33-one-hour-option.jpg) | 恢复1小时选项，测试选择控件连续切换 | 已核对 | 2026-10-25T00:22:06+08:00 |
| [34-cross-session-recall](34-cross-session-recall.jpg) | 新建另一知识会话后实际召回中文v6，使用跨会话事实服务 | 已核对 | 2026-10-25T00:22:37+08:00 |
| [35-real-partial-missing](35-real-partial-missing.jpg) | 最终生成prompt-v2返回PARTIAL：端口结论有E1支持，年假缺资料明确列出 | 已核对 | 2026-10-25T00:32:53+08:00 |
| [36-clarification](36-clarification.jpg) | 连续发送无错误码问题，明确需要澄清，不沿用上次证据 | 已核对 | 2026-10-25T00:32:54+08:00 |
| [37-no-evidence-refusal](37-no-evidence-refusal.jpg) | 年假资料不存在，真实模型明确资料不足且不携带旧引用 | 已核对 | 2026-10-25T00:34:50+08:00 |
| [38-refresh-citations-restored](38-refresh-citations-restored.jpg) | 刷新历史恢复3次回答及各自引用元数据，当前会话原文来源不会串位 | 已核对 | 2026-10-25T00:34:50+08:00 |
| [39-upgrade-source](39-upgrade-source.jpg) | 当前请求E1对应升级条件块，精确行号与排查请求不同 | 已核对 | 2026-10-25T00:37:17+08:00 |
| [40-request-local-e1](40-request-local-e1.jpg) | 上一请求E1仍正确对应排查块，不串成新请求E1升级块 | 已核对 | 2026-10-25T00:37:18+08:00 |
| [41-demo-context-preview](41-demo-context-preview.jpg) | 规则演示返回实际Alice确认偏好与证据，标签不是语义模型 | 已核对 | 2026-10-25T00:38:14+08:00 |
| [42-mode-stale-before-fix](42-mode-stale-before-fix.jpg) | 发现切换真实模式后旧demo结果尚未清空，后续修复并重跑 | 修复前问题 | 2026-10-25T00:38:14+08:00 |
| [43-mode-change-clears-fixed](43-mode-change-clears-fixed.jpg) | 修复后demo→real立即清空旧结果，不能把演示结果当真实模型 | 已核对 | 2026-10-25T00:39:21+08:00 |
| [44-real-context-record](44-real-context-record.jpg) | 真实模式预览出现SERVICE_FAILURE，失败卡明确显示并丢弃上下文；未伪装成功或零命中，后续重试另记 | 真实外部失败已明确处理 | 2026-10-25T00:39:58+08:00 |
| [45-real-preview-recovered](45-real-preview-recovered.jpg) | 重试后6511ms有依据回答，真实生成prompt-v2/检查prompt-v1，实际上下文仅Alice确认中文v6 | 已核对 | 2026-10-25T00:42:31+08:00 |
| [46-bob-after-real-preview](46-bob-after-real-preview.jpg) | 切换Bob后清除Alice旧预览，实际召回0，确认事实不会跨用户展示 | 已核对 | 2026-10-25T00:42:49+08:00 |
| [47-delete-confirmed](47-delete-confirmed.jpg) | 删除确认事实后立刻召回0，不从历史或旧缓存恢复 | 已核对 | 2026-10-25T00:43:08+08:00 |
| [48-delete-repeat-read](48-delete-repeat-read.jpg) | 删除后连续再次查询仍0，旧事实不可见 | 已核对 | 2026-10-25T00:43:09+08:00 |
| [49-clean-demo-facts](49-clean-demo-facts.jpg) | 删除本批拒绝的合成提议，Alice与Bob活动记忆恢复初始0；PG审计软删保留 | 已核对 | 2026-10-25T00:43:10+08:00 |
| [50-deleted-citation-blocked](50-deleted-citation-blocked.jpg) | 删除本批合成809资料后，再打开历史E1明确提示失效，不展示旧原文 | 已核对 | 2026-10-25T00:44:38+08:00 |
| [51-chat-english](51-chat-english.jpg) | 统一对话与回答状态切英文，用户原话/资料保留原语言 | 已核对 | 2026-10-25T00:45:37+08:00 |
| [52-memory-english](52-memory-english.jpg) | 事实面板英文按钮与空状态显示，0有效事实 | 已核对 | 2026-10-25T00:45:38+08:00 |
| [53-chat-chinese](53-chat-chinese.jpg) | 切回中文，原会话与证据元数据保留 | 已核对 | 2026-10-25T00:45:38+08:00 |
| [54-mobile-chat-memory](54-mobile-chat-memory.jpg) | 视口设置未应用到该后台页，实际仍桌面宽度；此图不计移动验收，真实390见56 | 非目标视口 | 2026-10-25T00:45:39+08:00 |
| [55-mobile-confirmed](55-mobile-confirmed.jpg) | 视口设置未应用到该后台页，实际仍桌面宽度；此图不计移动验收，真实390见56 | 非目标视口 | 2026-10-25T00:46:37+08:00 |
| [56-mobile-actual-390](56-mobile-actual-390.jpg) | 实际生效的第7页390宽响应式验收，DOM宽度390/scrollWidth390，无横向溢出 | 已核对 | 2026-10-25T00:47:29+08:00 |
| [57-graph-lesson14](57-graph-lesson14.jpg) | 第十四课工程已实现与原始语义门槛失败边界可读，个人0/28不变 | 已核对 | 2026-10-25T00:49:20+08:00 |
| [58-graph-one-hop](58-graph-one-hop.jpg) | 切换1跳，保留第十四课阅读窗与选择关系，未更改掌握状态 | 已核对 | 2026-10-25T00:49:48+08:00 |
| [59-graph-two-hops](59-graph-two-hops.jpg) | 切换2跳，保留第十四课阅读窗与选择关系，未更改掌握状态 | 已核对 | 2026-10-25T00:49:50+08:00 |
| [60-graph-full](60-graph-full.jpg) | 切换全图，保留第十四课阅读窗与选择关系，未更改掌握状态 | 已核对 | 2026-10-25T00:49:51+08:00 |
| [61-graph-2d](61-graph-2d.jpg) | 切换2D后本课阅读窗与关系仍一致 | 已核对 | 2026-10-25T00:49:52+08:00 |
| [62-graph-3d](62-graph-3d.jpg) | 切回3D，节点与连线继续显示，本课阅读窗保持 | 已核对 | 2026-10-25T00:49:54+08:00 |
| [63-graph-stage-filter](63-graph-stage-filter.jpg) | 按上下文与知识阶段筛选课次 | 已核对 | 2026-10-25T00:50:44+08:00 |
| [64-graph-kind-relation-filter](64-graph-kind-relation-filter.jpg) | 课程类型与先修关系组合筛选，验证过滤后关系 | 已核对 | 2026-10-25T00:50:44+08:00 |
| [65-graph-search-mcp](65-graph-search-mcp.jpg) | 搜索MCP可发现第十五课与新增协议/源码节点 | 已核对 | 2026-10-25T00:50:44+08:00 |
| [66-graph-reset-filters](66-graph-reset-filters.jpg) | 清空筛选后回到101实体141关系，个人仍0/28 | 已核对 | 2026-10-25T00:50:44+08:00 |
| [67-node-14-evidence](67-node-14-evidence.jpg) | 点击第十四课实现与证据边界，阅读标题和源码/任务证据正确 | 已核对 | 2026-10-25T00:52:53+08:00 |
| [68-node-answer-source](68-node-answer-source.jpg) | 点击引用回答与支持性源码，阅读标题和源码/任务证据正确 | 已核对 | 2026-10-25T00:52:57+08:00 |
| [69-node-memory-source](69-node-memory-source.jpg) | 点击PG确认事实与生命周期源码，阅读标题和源码/任务证据正确 | 已核对 | 2026-10-25T00:53:00+08:00 |
| [70-node-15-tasks](70-node-15-tasks.jpg) | 点击第十五课完整学习任务，阅读标题和源码/任务证据正确 | 已核对 | 2026-10-25T00:53:03+08:00 |
| [71-node-mcp-boundary](71-node-mcp-boundary.jpg) | 点击MCP协议与业务信任边界，阅读标题和源码/任务证据正确 | 已核对 | 2026-10-25T00:53:06+08:00 |
| [72-node-mcp-source](72-node-mcp-source.jpg) | 点击现有MCP结果适配源码，阅读标题和源码/任务证据正确 | 已核对 | 2026-10-25T00:53:10+08:00 |
| [73-graph-lesson15](73-graph-lesson15.jpg) | 第十五课完整六单元入口可读，工程待实现，个人未开始 | 已核对 | 2026-10-25T00:53:13+08:00 |
| [74-graph-english](74-graph-english.jpg) | 切换操作实际已生效为英文；工具回执超时后以页面状态核对 | 已核对 | 2026-10-25T00:53:58+08:00 |
| [75-graph-chinese](75-graph-chinese.jpg) | 英文切回中文，课程入口与个人0/28保持 | 已核对 | 2026-10-25T00:53:59+08:00 |
| [76-cancel-session-delete](76-cancel-session-delete.jpg) | 取消不可撤销的会话删除，随后以本地可恢复归档清理仅本批两会话 | 已核对 | 2026-10-25T00:55:03+08:00 |
| [77-original-history-restored](77-original-history-restored.jpg) | 恢复初始原有会话，原三条历史保留；本批两会话可恢复归档到本地忽略目录 | 已核对 | 2026-10-25T00:56:57+08:00 |
| [78-final-evidence-boundary](78-final-evidence-boundary.jpg) | 最终源码节点显示已实现及原始语义门槛失败边界 | 已核对 | 2026-10-25T01:00:59+08:00 |
| [79-next-lesson-ready](79-next-lesson-ready.jpg) | 第十五课中文任务入口，工程待实现、个人未开始 | 已核对 | 2026-10-25T01:01:02+08:00 |
