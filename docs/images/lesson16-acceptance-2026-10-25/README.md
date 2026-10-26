# 第十六课逐状态验收

51个实际操作状态，包含失败与修复重跑。真实成功草稿、无证据、版本回退、PG停机/恢复、不可信资料负例、移动布局、截止及三页面容量拒绝已验证；并发发现和来源等待分类已修复，最终后端97项、前端74项及生产构建通过。各层证据不能互相替代，完整边界见[实现记录](../../learning/16-implementation.md)。可打开[连续截图画廊](gallery.html)逐项查看。

| 操作 | 预期 | 实际 | 截图 |
| --- | --- | --- | --- |
| 选择IT工单分析并新建会话 | 显示默认v1与真实服务状态，原3会话保留 | 统一页面显示只读策略与待确认MCP状态，未运行模型 | [Screenshot](01-skill-default.jpg) |
| 刷新工具发现并展开定义详情 | MCP就绪，显示固定只读工具与schema摘要 | 真实READY，版本v1、四步骤、预算30000ms与只读knowledge工具显示 | [Screenshot](02-live-tool-and-definition.jpg) |
| 选择v2后刷新策略目录 | 新请求保留v2选择，目录仍显示默认v1 | 选择未被刷新覆盖；下一请求用v2，尚未发模型请求 | [Screenshot](03-select-v2.jpg) |
| v2发送VPN连接失败（无错误码） | 不调用工具或模型；返回澄清并保存v2快照 | 页面显示需要补充信息，明确尚未查询知识或创建工单 | [Screenshot](04-needs-input-v2.jpg) |
| 展开思考与执行，再展开本次快照 | 显示真实NEEDS_INPUT步骤和v2定义/工具摘要 | 事件和v2快照正确，但展开策略面板占据过多高度，执行卡被裁切，阅读空间不足；已记录为布局问题，待自动收起后重跑。 | [Screenshot](05-clarification-snapshot.jpg) |
| 选择v1并发送完整VPN401问题 | 请求启动后策略面板自动收起，为步骤和结果留出空间 | 策略摘要仍显示v1与真实服务状态，面板已收起，正在执行真实调用 | [Screenshot](06-run-panel-collapsed.jpg) |
| 真实v1完整问题第一次执行 | 当前证据生成建议与未创建草稿 | 实际15.5秒执行失败，未发布草稿；保留失败并排查，不能记作成功 | [Screenshot](07-real-run-failed.jpg) |
| 显式keyword真实对照请求 | 模型选择允许工具后生成有依据草稿 | 20.1秒失败且无TOOL_REQUESTED；未伪造调用或草稿，安全诊断粒度不足，待补分类后重跑 | [Screenshot](08-selection-failure.jpg) |
| 打开第16课所选知识 | 显示真实进行中状态与未完成验收项，不自动标掌握 | 111节点156关系；第16课明确两次真实失败待修复，工程与个人掌握分开 | [Screenshot](09-graph-in-progress.jpg) |
| 从第16课关系打开第17课 | 第17课完整任务入口存在，工程标记尚未实施 | 关联导航进入身份权限课程，任务包已发布，未把规划写成实现 | [Screenshot](10-next-lesson17.jpg) |
| 下一课切1跳再切2D | 保持第17课选择，显示直接关联 | 111/157全量背景信息与一跳局部视图分开，下一课阅读内容未串成第16课 | [Screenshot](11-next-lesson-2d.jpg) |
| 切换英文查看下一课 | 英文内容与中文相同边界，当前选择保持 | Trusted identity与任务包说明正确显示，工程仍未实施 | [Screenshot](12-next-lesson-english.jpg) |
| 搜索Skill并恢复全图范围 | 只显示匹配知识及有效关系，工程证据可见 | Skill概念、课程和新运行源码证据出现在筛选结果中，没有捏造额外关系 | [Screenshot](13-skill-search.jpg) |
| 恢复本批图谱验收前完整备份，再导出比较 | 所有个人笔记、掌握与阅读位置完全还原 | JSON深度比较完全一致；关闭阅读卡，保留中文全图3D状态 | [Screenshot](14-personal-progress-restored.jpg) |
| 受控环境默认版本切v2并重启，再刷新历史 | 新请求默认v2，旧历史仍保存原版本 | 目录显示服务端默认v2，原有三次运行仍各自保留快照，未按新默认改写 | [Screenshot](15-default-v2-published.jpg) |
| 默认v2执行原VPN401问题 | 真实模型选工具、当前证据和未创建草稿均成功 | 21.5秒成功，v2四步骤完成，实际MCP工具与ticket-draft-v2、NORMAL优先级可查看，明确尚未创建工单 | [Screenshot](16-live-v2-draft.jpg) |
| 收起草稿内容查看四步总览 | 四步真实完成，草稿标明未创建 | v2步骤、耗时和未创建草稿摘要可连续阅读 | [Screenshot](17-live-v2-steps.jpg) |
| 重新展开草稿并查看输出版本 | v2结构含NORMAL且created=false | 草稿字段、优先级NORMAL、ticket-draft-v2与未创建提示显示，未触发写操作 | [Screenshot](18-live-v2-draft-details.jpg) |
| 展开成功调用的参数与结果 | 显示模型真实query/mode/topK及当前证据入口 | 真实hybrid检索VPN 401 topK3，工具结果与引用入口来自MCP事件 | [Screenshot](19-v2-real-tool-parameters.jpg) |
| 打开C1当前原文 | 核对文档版本/generation/chunk/行范围与原文一致 | C1 L6–8当前原文确有凭据过期和统一身份平台重新登录，支持回答第一条 | [Screenshot](20-current-source-c1.jpg) |
| 关闭C1并打开C3 | 来源切换为工单升级段，支持回答其余两条 | 原文明示连续两次失败后提交、时间/版本/脱敏错误码及禁止密码/验证码/令牌，三条建议逐项支持 | [Screenshot](21-current-source-c3.jpg) |
| 关闭原文后再次打开C1 | 重新核对当前来源，不沿用上次C3内容 | 原文重新显示VPN401凭据段，源切换未串用上次段落 | [Screenshot](22-source-reopened.jpg) |
| 唯一不存在标记的keyword请求 | 查无资料后NO_EVIDENCE且无草稿 | 实际在工具请求前失败，保留安全诊断；此步不算无证据验收通过 | [Screenshot](23-empty-query-model-failure.jpg) |
| 停止本项目MCP服务后刷新工具发现 | 显示不可用，不把空工具列表当成功 | 策略版本仍已启用，MCP实际状态单独显示不可用，发现工具0并保留安全错误码 | [Screenshot](24-mcp-service-offline.jpg) |
| MCP停止期间执行正常策略问题 | 连接失败受控结束，不调用模型或发布草稿 | 11.6秒后在发现阶段FAILED结束，公开诊断SKILL_EXECUTION_FAILED；无TOOL_REQUESTED和草稿，服务状态自动更新为不可用。未将其写成超时成功；发现阶段诊断仍过于笼统，待补强。 | [Screenshot](25-offline-run-rejected.jpg) |
| 重启后查看服务端默认版本和保留的历史回答 | 默认版本v1，历史v2结果不被改写 | 页面默认v1，历史成功回答和其来源保留；MCP尚未重新发现 | [Screenshot](26-default-v1-rollback.jpg) |
| 默认回退v1后重新运行不存在标记的keyword检索，展开参数与结果 | 真实工具返回空证据，终态NO_EVIDENCE，无草稿；结束自动更新MCP状态 | 实际10.0s完成，工具847ms，SKILL_NO_EVIDENCE，无草稿，面板MCP已就绪 | [Screenshot](27-no-evidence-v1-and-status-recovery.jpg) |
| 执行中点击停止接收，等待服务端保存，重新展开记录 | 停止接收后保留真实已发生工具事件，不发布草稿，可再次发送 | 已停止接收，历史重新加载，工具事件保留，发送恢复；停止不等于远程回滚 | [Screenshot](28-local-stop-and-restored-history.jpg) |
| 默认回退v1后执行真实keyword查询并展开草稿 | 新草稿为ticket-draft-v1，不带v2优先级，未创建且需审批 | 20.7s四步完成，实际工具1258ms，ticket-draft-v1无优先级字段；模型称证据无草稿模板，Java仍按确定性结构生成草稿，该语义局限保留 | [Screenshot](29-v1-draft-after-rollback.jpg) |
| 中文切换英文，查看现有v1草稿 | 控件、步骤、状态及草稿字段切换英文，历史原文保留 | Skill execution record / Succeeded / Not created / Human approval required及字段英文，用户与模型原文不改写 | [Screenshot](30-english-skill-draft.jpg) |
| 实际停止15432课堂PG后发送keyword知识请求，展开真实返回 | PG不可用时明确失败，不回退内存，不发布草稿 | 5.7s执行异常，真实工具返回数据库不可用，无草稿；MCP连接就绪不代表数据库可用 | [Screenshot](31-real-pg-offline-no-fallback.jpg) |
| 重启同一PG集群，原MCP与主服务不重启，再发相同业务查询 | 恢复真实当前知识检索并生成v1未创建草稿 | 21.8s四步完成，使用vpn-guide当前来源，未创建且需审批；模型建议与Java草稿职责局限保留 | [Screenshot](32-real-pg-restored-v1-success.jpg) |
| 390x844新标签恢复历史并展开执行卡 | 策略与执行内容均可阅读，页面无横向溢出 | 实际body宽390/scroll390，但策略默认展开占高过多，执行卡空间不足；作为失败记录，待修复重跑 | [Screenshot](33-mobile-panel-too-tall.jpg) |
| 导入本课专属合成不可信指令资料 | 发布后以真实工具命中负例 | 导入OPERATION_FAILED，没有宣称发布成功；原vpn-guide保留 | [Screenshot](34-synthetic-import-failed.jpg) |
| 同ID重新导入合成负例，查看当前原文分块 | 只发布自己的验收文档，原VPN手册保持 | lesson16-injection-20261026版本2、当前1分块含明确越权指令；vpn-guide仍版本2 | [Screenshot](35-synthetic-untrusted-source-published.jpg) |
| 对已发布合成负例运行keyword检索，展开实际结果 | 只允许只读查询，证据校验拒绝越权指令，不生成草稿 | 实际命中合成资料后执行失败，无管理员调用及草稿；公开通用失败码，具体已知规则拒绝需结合自动测试与日志，不能泛称防御所有注入 | [Screenshot](36-untrusted-source-rejected.jpg) |
| 确认删除自己的合成负例并核对文档清单 | 合成文档失效，原VPN有效版本不改变 | 合成文档DELETED v3零分块，1有效文档，vpn-guide ACTIVE v2四分块 | [Screenshot](37-synthetic-source-cleaned.jpg) |
| 390x844重载默认收起策略面板，展开成功运行并滚动至草稿输出版本 | 聊天区保持可阅读高度，无页面横向溢出，草稿字段可读 | body375/scroll375（390视口含滚动条），策略默认收起；执行区最小360px，v1草稿可滚动阅读 | [Screenshot](38-mobile-draft-fixed.jpg) |
| 移动端手动展开策略及只读工具详情 | 详情可读，执行区不再被压缩至60px | 手动展开受支持，页面纵向延展，执行区保底高度保留 | [Screenshot](39-mobile-policy-manual-expand.jpg) |
| 并发真实查询后展开本会话截止结果 | 步骤/整体预算耗尽应明确超时，不发布草稿 | 实际30.1s结束，SKILL_DEADLINE，无草稿；另两受控请求也真实超时，不记录为成功或容量拒绝 | [Screenshot](40-concurrent-deadline-no-draft.jpg) |
| 三个独立页面快速连续发送同类业务请求，展开第三项结果 | 两名额占用时第三项立即容量拒绝，不调用新工具或发布草稿 | 实际0.1s，SKILL_CAPACITY/服务繁忙，无工具卡及草稿；其余两项各自仍在独立会话执行 | [Screenshot](41-three-pages-capacity-rejection.jpg) |
| 默认回退v1后重新打开旧v2运行快照 | 旧运行仍保存v2定义和草稿，不由当前默认改写 | 旧快照it-ticket-analysis / v2及摘要、ticket-draft-v2/NORMAL保留；当前默认v1 | [Screenshot](42-old-v2-snapshot-after-v1-rollback.jpg) |
| 切换已有恋爱助手演示历史，不发送新消息 | 原Agent和历史对应，IT策略/草稿不串入 | 页面显示恋爱助手原演示，无Skill面板或IT草稿，未修改原会话 | [Screenshot](43-agent-history-isolation.jpg) |
| 回到IT会话选择v2并刷新策略列表 | 刷新保留v2选择，仅影响下次请求；旧v1容量结果保持 | 下一请求v2，旧运行仍按各自版本保存；未发送额外请求 | [Screenshot](44-version-selection-keeps-history.jpg) |
| 打开更新后的第16课阅读卡 | 显示实际Skill实现、快照竞态修复及证据边界，个人未掌握不变 | 111节点157关系，当前仍标验收中等待复核修复；个人未开始/0已掌握未自动变更 | [Screenshot](45-graph-updated-runtime-evidence.jpg) |
| 第16课切一跳2D，再沿先修关系打开第17课 | 下一课完整任务关联正确，工程未实施，2D保持选择 | 进入身份权限课程，完整六单元任务与代码落点显示，尚未实施 | [Screenshot](46-graph-next-lesson17.jpg) |
| 下一课保持一跳2D并切英文 | 同一课程英文内容、先修关系和待实施边界对应 | 英文Trusted identity/tenant/policy task pack显示，未把任务包记成工程实现 | [Screenshot](47-graph-next-lesson-english.jpg) |
| 中文搜索Skill，核对课程、概念和源码证据 | 筛选保留有效实体与关系，阅读不改变个人掌握 | 显示Skill课程、版本/范围概念与实际运行证据；个人仍0/28已掌握 | [Screenshot](48-graph-skill-filter.jpg) |
| 最终回归后查看第16课工程完成状态 | 工程完成与个人掌握分开，当前源码与下一课路径对应 | 第16课工程已完成，个人未开始/0掌握保留；实现记录提供97项最终专项及证据边界 | [Screenshot](49-lesson16-engineering-completed.jpg) |
| 完整恢复本轮开始前备份，再次导出逐值比较 | 个人掌握、笔记及阅读位置完全一致，工程状态可独立更新 | 导出JSON逐值深度比较完全一致；中文全图3D、阅读卡关闭 | [Screenshot](50-final-personal-progress-restored.jpg) |
| 移走3个已空闲验收会话至可恢复备份后刷新页面 | 仅原3演示保留，历史未改写，服务可继续使用 | 页面3条原演示、原时间工具与昵称历史保留；验收文件逐项哈希校验后移至target | [Screenshot](51-original-conversations-preserved.jpg) |
