# 第十二课与学习图谱：2026-10-24 浏览器验收

## 结论与范围

真实浏览器操作 `/learning` 和 `/knowledge`，每次关键操作后读取页面状态并保存截图。原87个节点及新增3个节点均逐个点击、核对阅读标题，逐节点文件共90张。人工视觉抽查批次末图及业务关键画面；没有宣称逐张人工审阅、全部浏览器兼容或无限组合穷举。

- 知识库：连接/刷新、合成示例、导入、重复去重、分块/原文、关闭/重开、编辑更新、Top K 1/3/5/10、真实HTTP检索、来源核对、删除取消/Escape/确认、中英文、390px无横向溢出。
- 图谱：类型6项、阶段8项、掌握4项、关系8项（含全部恢复），一跳/二跳/全图、2D/3D、缩放/适应/重排、标签、动态暂停/恢复/减弱、全屏、阅读窗指针拖动/键盘移动/复位、复制路径、关系导航、双语、导出/导入及错误格式。个人掌握状态保持0/28。
- 数据库：真实PG16.15/vector0.8.6。停止36376后新进程45708，同数据目录读取版本2同revision四块；独立知识服务也重启。停库时503 STORAGE_UNAVAILABLE，恢复后重新检索成功，无内存回退。
- 实际模型：DashScope text-embedding-v3 1024D。独立探针2次调用只验证闭环；页面另有真实导入/更新/检索请求。VPN查询第一条命中故障章节，余弦距离约0.2296；更新后工单问题命中新版“两次”，不是旧“三次”。这不是固定评测集的召回质量验收。

## 发现、修复与替代证据

1. 搜索清空后选中星点在屏外（旧38）：等待真实布局稳定并拒绝空坐标集合后重聚焦；41中选中标签可见，x约678，原失败x约2073。
2. 2D/3D重建丢失选中高亮：初始化从reading/selected恢复；30/31、45/55回归。27/28被开发服务重载中断，不能作为通过证据。
3. 停库却宣称旧版可用（k27）：修为503并明确“当前状态无法确认”；k33。连接未知的数量最终显示“—”。
4. 改查询后旧命中残留：按问题/TopK变化清除结果；k31→k32。刷新也清除未确认清单与详情。
5. k23第一次清空操作未真正清空输入，不能作为禁用通过证据；k23b和k32重新全选删除并验证。
6. SVG环形菜单按钮中心落在扇区空洞时，自动点击未触发；49/50不作为收起成功证据。50b/50c/51/52用真实键盘Enter执行，分别10节点→1节点→展开/隐藏，53恢复。
7. 第一轮filter-*受到搜索文本干扰；filter2-*在确认搜索为空后重新执行全部选项，以后者为准。

## 边界

本报告只覆盖本机当前浏览器、教学租户、合成资料；未进行真实企业授权、恶意并发流量、跨浏览器或生产负载验证。学习进度导出文件和凭据未提交。首次重启故障注入测试曾因旧行触发非本租户CHECK而失败，已将测试约束限定本次租户，重复运行通过。构建仍有三维依赖包体积警告。

## 截图索引

每行链接为实际浏览器原始截图；保留中间态以便追溯。详细状态以文件说明和上面的替代规则为准，不把“已截取”一概当成“验收通过”。

| 截图 | 操作与观察 |
| --- | --- |
| [01-initial-3d](01-initial-3d.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [02-catalogue-open](02-catalogue-open.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [03-lesson01-reader](03-lesson01-reader.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [04-one-hop](04-one-hop.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [05-two-hop](05-two-hop.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [06-center-selection](06-center-selection.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [07-full-graph](07-full-graph.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [08-fit-all](08-fit-all.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [09-relayout](09-relayout.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [10-labels-on](10-labels-on.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [11-labels-off](11-labels-off.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [12-motion-paused](12-motion-paused.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [13-motion-resumed](13-motion-resumed.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [14-reduced-motion](14-reduced-motion.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [15-reduced-motion-off](15-reduced-motion-off.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [16-fullscreen](16-fullscreen.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [17-exit-fullscreen](17-exit-fullscreen.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [18-switch-2d](18-switch-2d.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [19-2d-zoom-in](19-2d-zoom-in.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [20-2d-zoom-out](20-2d-zoom-out.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [21-2d-fit](21-2d-fit.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [22-2d-reset](22-2d-reset.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [23-2d-read](23-2d-read.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [24-reader-close](24-reader-close.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [25-2d-node-click](25-2d-node-click.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [26-return-3d](26-return-3d.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [27-2d-selection-remount](27-2d-selection-remount.jpg) | 【中间态/已替代】操作后页面截图；含义见文件名及覆盖范围。 |
| [28-3d-selection-remount](28-3d-selection-remount.jpg) | 【中间态/已替代】操作后页面截图；含义见文件名及覆盖范围。 |
| [29-catalogue-reopen](29-catalogue-reopen.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [30-2d-focus-restored](30-2d-focus-restored.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [31-3d-focus-restored](31-3d-focus-restored.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [32-export-progress](32-export-progress.jpg) | 导出学习进度：浏览器下载事件成功 |
| [33-export-graph](33-export-graph.jpg) | 导出图谱JSON：浏览器下载事件成功 |
| [34-import-progress](34-import-progress.jpg) | 导入刚导出的原学习进度：状态保持0/28 |
| [35-import-invalid](35-import-invalid.jpg) | 无效进度文件明确报错，个人掌握数仍为0/28 |
| [36-search-matched](36-search-matched.jpg) | 搜索上下文：只显示匹配节点 |
| [37-search-empty](37-search-empty.jpg) | 搜索无结果：显示空状态 |
| [38-search-clear](38-search-clear.jpg) | 【中间态/已替代】清空搜索：恢复全部节点 |
| [39-focus-search-match](39-focus-search-match.jpg) | 新代码搜索上下文 |
| [40-focus-search-empty](40-focus-search-empty.jpg) | 新代码搜索空结果 |
| [41-focus-clear-fixed](41-focus-clear-fixed.jpg) | 清空搜索后聚焦回归，检查选中标签实际位置 |
| [42-one-hop-final](42-one-hop-final.jpg) | 选中lesson12一跳展开 |
| [43-two-hop-final](43-two-hop-final.jpg) | lesson12二跳扩展 |
| [44-full-final](44-full-final.jpg) | 恢复90节点全图 |
| [45-2d-final](45-2d-final.jpg) | 切换2D保持lesson12详情 |
| [46-2d-ring](46-2d-ring.jpg) | 二维节点环形操作菜单 |
| [47-pin](47-pin.jpg) | 固定二维选中节点 |
| [48-unpin](48-unpin.jpg) | 取消节点固定 |
| [49-collapse](49-collapse.jpg) | 【中间态/已替代】节点菜单收起邻居 |
| [50-collapse-root](50-collapse-root.jpg) | 【中间态/已替代】一跳根节点收起为单点 |
| [50b-collapse-keyboard](50b-collapse-keyboard.jpg) | 使用环形扇区键盘Enter执行；中心坐标落空记录替代 |
| [50c-collapsed](50c-collapsed.jpg) | 一跳收起后仅根节点 |
| [51-expand-root](51-expand-root.jpg) | 展开lesson12直接邻居 |
| [52-dismiss-node](52-dismiss-node.jpg) | 从画布隐藏根节点 |
| [53-restore-hidden](53-restore-hidden.jpg) | 恢复被隐藏节点 |
| [54-return-full](54-return-full.jpg) | 恢复全图 |
| [55-return-3d](55-return-3d.jpg) | 再次切回3D保持选中邻域 |
| [56-reader-keyboard-move](56-reader-keyboard-move.jpg) | 方向键移动阅读窗并显示回到节点旁按钮 |
| [57-reader-reset](57-reader-reset.jpg) | 回到节点旁重置窗口 |
| [58-copy-path](58-copy-path.jpg) | 复制第十三课文档路径 |
| [59-reader-pointer-drag](59-reader-pointer-drag.jpg) | 标题栏指针拖动后显示复位按钮 |
| [60-reader-pointer-reset](60-reader-pointer-reset.jpg) | 指针拖动后复位 |
| [61-related-evidence](61-related-evidence.jpg) | 点击关系定位第十三课任务包 |
| [62-graph-english](62-graph-english.jpg) | 图谱与新课程证据节点英文阅读 |
| [filter-kind-all](filter-kind-all.jpg) | 【中间态/已替代】恢复节点类型全部 |
| [filter-kind-concept](filter-kind-concept.jpg) | 【中间态/已替代】筛选 节点类型=概念，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-kind-evidence](filter-kind-evidence.jpg) | 【中间态/已替代】筛选 节点类型=源码 / 文档，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-kind-lesson](filter-kind-lesson.jpg) | 【中间态/已替代】筛选 节点类型=课程，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-kind-source](filter-kind-source.jpg) | 【中间态/已替代】筛选 节点类型=参考资料，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-kind-topic](filter-kind-topic.jpg) | 【中间态/已替代】筛选 节点类型=阶段，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-progress-all](filter-progress-all.jpg) | 【中间态/已替代】恢复我的掌握情况全部 |
| [filter-progress-inProgress](filter-progress-inProgress.jpg) | 【中间态/已替代】筛选 我的掌握情况=学习中，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-progress-mastered](filter-progress-mastered.jpg) | 【中间态/已替代】筛选 我的掌握情况=已掌握，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-progress-notStarted](filter-progress-notStarted.jpg) | 【中间态/已替代】筛选 我的掌握情况=未开始，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-relation-all](filter-relation-all.jpg) | 【中间态/已替代】恢复关系类型全部 |
| [filter-relation-contains](filter-relation-contains.jpg) | 【中间态/已替代】筛选 关系类型=阶段包含，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-relation-documented-by](filter-relation-documented-by.jpg) | 【中间态/已替代】筛选 关系类型=课程记录，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-relation-documents](filter-relation-documents.jpg) | 【中间态/已替代】筛选 关系类型=资料记录，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-relation-implements](filter-relation-implements.jpg) | 【中间态/已替代】筛选 关系类型=代码实现，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-relation-informed-by](filter-relation-informed-by.jpg) | 【中间态/已替代】筛选 关系类型=参考来源，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-relation-prerequisite](filter-relation-prerequisite.jpg) | 【中间态/已替代】筛选 关系类型=先修关系，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-relation-related](filter-relation-related.jpg) | 【中间态/已替代】筛选 关系类型=相关概念，页面反馈 generic: 1 个节点 · 0 条关系 |
| [filter-stage-all](filter-stage-all.jpg) | 【中间态/已替代】恢复学习阶段全部 |
| [filter-stage-stage-01](filter-stage-stage-01.jpg) | 【中间态/已替代】筛选 学习阶段=单 Agent 执行链路，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-stage-stage-02](filter-stage-stage-02.jpg) | 【中间态/已替代】筛选 学习阶段=多 Agent 编排可靠性，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-stage-stage-03](filter-stage-stage-03.jpg) | 【中间态/已替代】筛选 学习阶段=持久化与故障恢复，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-stage-stage-04](filter-stage-stage-04.jpg) | 【中间态/已替代】筛选 学习阶段=上下文与知识能力，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-stage-stage-05](filter-stage-stage-05.jpg) | 【中间态/已替代】筛选 学习阶段=安全工具与业务执行，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-stage-stage-06](filter-stage-stage-06.jpg) | 【中间态/已替代】筛选 学习阶段=观察、评估与成本，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter-stage-stage-07](filter-stage-stage-07.jpg) | 【中间态/已替代】筛选 学习阶段=交付与面试，页面反馈 generic: 0 个节点 · 0 条关系 |
| [filter2-kind-all](filter2-kind-all.jpg) | 恢复节点类型全部 |
| [filter2-kind-concept](filter2-kind-concept.jpg) | 筛选 节点类型=概念，页面反馈 generic: 22 个节点 · 13 条关系 |
| [filter2-kind-evidence](filter2-kind-evidence.jpg) | 筛选 节点类型=源码 / 文档，页面反馈 generic: 30 个节点 · 0 条关系 |
| [filter2-kind-lesson](filter2-kind-lesson.jpg) | 筛选 节点类型=课程，页面反馈 generic: 28 个节点 · 29 条关系 |
| [filter2-kind-source](filter2-kind-source.jpg) | 筛选 节点类型=参考资料，页面反馈 generic: 3 个节点 · 0 条关系 |
| [filter2-kind-topic](filter2-kind-topic.jpg) | 筛选 节点类型=阶段，页面反馈 generic: 7 个节点 · 0 条关系 |
| [filter2-progress-all](filter2-progress-all.jpg) | 恢复我的掌握情况全部 |
| [filter2-progress-inProgress](filter2-progress-inProgress.jpg) | 筛选 我的掌握情况=学习中，页面反馈 generic: 62 个节点 · 29 条关系 |
| [filter2-progress-mastered](filter2-progress-mastered.jpg) | 筛选 我的掌握情况=已掌握，页面反馈 generic: 62 个节点 · 29 条关系 |
| [filter2-progress-notStarted](filter2-progress-notStarted.jpg) | 筛选 我的掌握情况=未开始，页面反馈 generic: 90 个节点 · 127 条关系 |
| [filter2-relation-all](filter2-relation-all.jpg) | 恢复关系类型全部 |
| [filter2-relation-contains](filter2-relation-contains.jpg) | 筛选 关系类型=阶段包含，页面反馈 generic: 90 个节点 · 28 条关系 |
| [filter2-relation-documented-by](filter2-relation-documented-by.jpg) | 筛选 关系类型=课程记录，页面反馈 generic: 90 个节点 · 18 条关系 |
| [filter2-relation-documents](filter2-relation-documents.jpg) | 筛选 关系类型=资料记录，页面反馈 generic: 90 个节点 · 4 条关系 |
| [filter2-relation-implements](filter2-relation-implements.jpg) | 筛选 关系类型=代码实现，页面反馈 generic: 90 个节点 · 12 条关系 |
| [filter2-relation-informed-by](filter2-relation-informed-by.jpg) | 筛选 关系类型=参考来源，页面反馈 generic: 90 个节点 · 2 条关系 |
| [filter2-relation-prerequisite](filter2-relation-prerequisite.jpg) | 筛选 关系类型=先修关系，页面反馈 generic: 90 个节点 · 29 条关系 |
| [filter2-relation-related](filter2-relation-related.jpg) | 筛选 关系类型=相关概念，页面反馈 generic: 90 个节点 · 34 条关系 |
| [filter2-stage-all](filter2-stage-all.jpg) | 恢复学习阶段全部 |
| [filter2-stage-stage-01](filter2-stage-stage-01.jpg) | 筛选 学习阶段=单 Agent 执行链路，页面反馈 generic: 5 个节点 · 7 条关系 |
| [filter2-stage-stage-02](filter2-stage-stage-02.jpg) | 筛选 学习阶段=多 Agent 编排可靠性，页面反馈 generic: 5 个节点 · 7 条关系 |
| [filter2-stage-stage-03](filter2-stage-stage-03.jpg) | 筛选 学习阶段=持久化与故障恢复，页面反馈 generic: 3 个节点 · 3 条关系 |
| [filter2-stage-stage-04](filter2-stage-stage-04.jpg) | 筛选 学习阶段=上下文与知识能力，页面反馈 generic: 5 个节点 · 7 条关系 |
| [filter2-stage-stage-05](filter2-stage-stage-05.jpg) | 筛选 学习阶段=安全工具与业务执行，页面反馈 generic: 6 个节点 · 8 条关系 |
| [filter2-stage-stage-06](filter2-stage-stage-06.jpg) | 筛选 学习阶段=观察、评估与成本，页面反馈 generic: 5 个节点 · 7 条关系 |
| [filter2-stage-stage-07](filter2-stage-stage-07.jpg) | 筛选 学习阶段=交付与面试，页面反馈 generic: 6 个节点 · 8 条关系 |
| [k01-empty-connected](k01-empty-connected.jpg) | 真实 PostgreSQL 16.15 / pgvector0.8.6 / HTTP1024D 空清单 |
| [k02-refresh](k02-refresh.jpg) | 刷新真实连接成功 |
| [k03-sample](k03-sample.jpg) | 填入合成 VPN 教材示例 |
| [k04-import-published](k04-import-published.jpg) | 真实HTTP模型导入完成，PostgreSQL发布有效版本 |
| [k05-raw-text](k05-raw-text.jpg) | 切换完整原文 |
| [k06-chunks](k06-chunks.jpg) | 四个分块与精确UTF16位置 |
| [k07-duplicate](k07-duplicate.jpg) | 相同原文去重，版本保持1 |
| [k08-search-real](k08-search-real.jpg) | 真实1024D查询返回来源与距离 |
| [k09-inspect-origin](k09-inspect-origin.jpg) | 检索命中核对原文打开对应文档 |
| [k10-close-detail](k10-close-detail.jpg) | 关闭文档详情 |
| [k11-reopen-detail](k11-reopen-detail.jpg) | 从文档清单重开详情 |
| [k12-edit-load](k12-edit-load.jpg) | 载入现有原文供更新 |
| [k13-version-two](k13-version-two.jpg) | 修改后版本2四块，旧版本不再有效 |
| [k14-topk-one](k14-topk-one.jpg) | 切换TopK为1 |
| [k15-updated-search](k15-updated-search.jpg) | Top1命中新版本两次升级，旧三次段落未返回 |
| [k16-delete-fixture](k16-delete-fixture.jpg) | 独立合成删除测试资料已发布 |
| [k17-delete-dialog](k17-delete-dialog.jpg) | 删除确认窗口展示资料名称及清理范围 |
| [k18-delete-cancel](k18-delete-cancel.jpg) | 取消删除保持ACTIVE |
| [k19-delete-escape](k19-delete-escape.jpg) | Escape关闭确认窗口，资料未删除 |
| [k20-deleted](k20-deleted.jpg) | 删除后DELETED、0分块、VPN有效文档仍在 |
| [k21-topk-ten](k21-topk-ten.jpg) | TopK10用于检查全部有效分块 |
| [k22-deleted-not-searchable](k22-deleted-not-searchable.jpg) | Top10仅返回VPN四片段，无已删除doc命中 |
| [k23-empty-search-disabled](k23-empty-search-disabled.jpg) | 【中间态/已替代】空问题禁用检索按钮 |
| [k23b-empty-search-verified](k23b-empty-search-verified.jpg) | 使用全选删除后空问题、检索按钮禁用，替代k23 |
| [k24-english](k24-english.jpg) | 整页英文控件，资料正文保留原文 |
| [k25-chinese](k25-chinese.jpg) | 切回中文 |
| [k26-mobile](k26-mobile.jpg) | 390px知识库响应式布局 |
| [k27-database-unavailable](k27-database-unavailable.jpg) | 真实停止PG，连接与模型显示未确认，无内存回退；错误文案随后修正 |
| [k28-postgres-restarted](k28-postgres-restarted.jpg) | PG进程重启后VPN版本2四块与DELETED状态仍保留 |
| [k29-persisted-detail](k29-persisted-detail.jpg) | PG PID36376→45708，同revision2575dcee原文/四块保留 |
| [k30-topk-three](k30-topk-three.jpg) | TopK3；查询变化立即清除旧结果 |
| [k31-top3-results](k31-top3-results.jpg) | 真实HTTP Top3返回3条 |
| [k32-query-change-clears](k32-query-change-clears.jpg) | 清空问题即时清空结果且禁用查询 |
| [k33-outage-corrected](k33-outage-corrected.jpg) | 503 STORAGE_UNAVAILABLE，当前状态无法确认，无旧检索或有效清单 |
| [k34-recovered](k34-recovered.jpg) | 恢复PG与服务后版本2四块正常显示 |
| [k35-final-real-search](k35-final-real-search.jpg) | 服务和PG重启后的真实检索Top3成功 |
| [knowledge-final-viewport](knowledge-final-viewport.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [knowledge-overview](knowledge-overview.jpg) | 操作后页面截图；含义见文件名及覆盖范围。 |
| [lesson12-final](lesson12-final.jpg) | 最新第十二课阅读卡 |
| [lesson12-reader](lesson12-reader.jpg) | 第十二课PG实现状态、源码和验收说明 |
| [lesson13-reader](lesson13-reader.jpg) | 第十三课待实现，完整任务及评测目标 |
| [node-001](node-001.jpg) | 点击 lesson-01：模型、消息与上下文；阅读标题与目录项一致。 |
| [node-002](node-002.jpg) | 点击 lesson-02：真实工具调用；阅读标题与目录项一致。 |
| [node-003](node-003.jpg) | 点击 lesson-03：执行循环与工具结果契约；阅读标题与目录项一致。 |
| [node-004](node-004.jpg) | 点击 lesson-04：会话、历史存储与上下文恢复；阅读标题与目录项一致。 |
| [node-005](node-005.jpg) | 点击 lesson-05：Plan、Task 与依赖数据流；阅读标题与目录项一致。 |
| [node-006](node-006.jpg) | 点击 lesson-06：生命周期与失败传播；阅读标题与目录项一致。 |
| [node-007](node-007.jpg) | 点击 lesson-07：共享截止时间、取消与并发容量；阅读标题与目录项一致。 |
| [node-008](node-008.jpg) | 点击 lesson-08：重试、退避与业务幂等；阅读标题与目录项一致。 |
| [node-009](node-009.jpg) | 点击 lesson-09：运行数据建模与任务恢复；阅读标题与目录项一致。 |
| [node-010](node-010.jpg) | 点击 lesson-10：持久化工作流与协调方式选型；阅读标题与目录项一致。 |
| [node-011](node-011.jpg) | 点击 lesson-11：上下文工程、裁剪与摘要；阅读标题与目录项一致。 |
| [node-012](node-012.jpg) | 点击 lesson-12：知识接入、分块与增量索引；阅读标题与目录项一致。 |
| [node-013](node-013.jpg) | 点击 lesson-13：检索、混合召回与重排；阅读标题与目录项一致。 |
| [node-014](node-014.jpg) | 点击 lesson-14：引用、拒答与长期记忆；阅读标题与目录项一致。 |
| [node-015](node-015.jpg) | 点击 lesson-15：工具契约与 MCP；阅读标题与目录项一致。 |
| [node-016](node-016.jpg) | 点击 lesson-16：Skills 与版本化业务策略；阅读标题与目录项一致。 |
| [node-017](node-017.jpg) | 点击 lesson-17：身份、权限与租户隔离；阅读标题与目录项一致。 |
| [node-018](node-018.jpg) | 点击 lesson-18：提示注入防护与运行隔离；阅读标题与目录项一致。 |
| [node-019](node-019.jpg) | 点击 lesson-19：人工审批与受控业务写入；阅读标题与目录项一致。 |
| [node-020](node-020.jpg) | 点击 lesson-20：执行事件、SSE 与断线恢复；阅读标题与目录项一致。 |
| [node-021](node-021.jpg) | 点击 lesson-21：日志、指标与分布式追踪；阅读标题与目录项一致。 |
| [node-022](node-022.jpg) | 点击 lesson-22：Agent 评估与持续改进；阅读标题与目录项一致。 |
| [node-023](node-023.jpg) | 点击 lesson-23：预算、模型路由与降级；阅读标题与目录项一致。 |
| [node-024](node-024.jpg) | 点击 lesson-24：可复现部署与交付；阅读标题与目录项一致。 |
| [node-025](node-025.jpg) | 点击 lesson-25：容量、性能与背压；阅读标题与目录项一致。 |
| [node-026](node-026.jpg) | 点击 lesson-26：故障演练与恢复验证；阅读标题与目录项一致。 |
| [node-027](node-027.jpg) | 点击 lesson-27：完整业务链验收与架构复盘；阅读标题与目录项一致。 |
| [node-028](node-028.jpg) | 点击 lesson-28：面试复盘与独立系统设计；阅读标题与目录项一致。 |
| [node-029](node-029.jpg) | 点击 stage-01：单 Agent 执行链路；阅读标题与目录项一致。 |
| [node-030](node-030.jpg) | 点击 stage-02：多 Agent 编排可靠性；阅读标题与目录项一致。 |
| [node-031](node-031.jpg) | 点击 stage-03：持久化与故障恢复；阅读标题与目录项一致。 |
| [node-032](node-032.jpg) | 点击 stage-04：上下文与知识能力；阅读标题与目录项一致。 |
| [node-033](node-033.jpg) | 点击 stage-05：安全工具与业务执行；阅读标题与目录项一致。 |
| [node-034](node-034.jpg) | 点击 stage-06：观察、评估与成本；阅读标题与目录项一致。 |
| [node-035](node-035.jpg) | 点击 stage-07：交付与面试；阅读标题与目录项一致。 |
| [node-036](node-036.jpg) | 点击 concept-agent-loop：Agent 循环与 ReAct；阅读标题与目录项一致。 |
| [node-037](node-037.jpg) | 点击 concept-tool-contract：工具契约；阅读标题与目录项一致。 |
| [node-038](node-038.jpg) | 点击 concept-dag：DAG 与依赖数据流；阅读标题与目录项一致。 |
| [node-039](node-039.jpg) | 点击 concept-state：状态机与终态不变量；阅读标题与目录项一致。 |
| [node-040](node-040.jpg) | 点击 concept-cancel：截止时间、取消与资源所有权；阅读标题与目录项一致。 |
| [node-041](node-041.jpg) | 点击 concept-idempotency：重试、退避与幂等；阅读标题与目录项一致。 |
| [node-042](node-042.jpg) | 点击 concept-rag：RAG 与证据链；阅读标题与目录项一致。 |
| [node-043](node-043.jpg) | 点击 concept-authorization：认证、授权与租户边界；阅读标题与目录项一致。 |
| [node-044](node-044.jpg) | 点击 concept-human-approval：人工审批与副作用边界；阅读标题与目录项一致。 |
| [node-045](node-045.jpg) | 点击 concept-observability：事件、指标、Trace 与评估；阅读标题与目录项一致。 |
| [node-046](node-046.jpg) | 点击 concept-knowledge-graph：知识图谱建模：实体与关系；阅读标题与目录项一致。 |
| [node-047](node-047.jpg) | 点击 concept-graph-validation：知识图谱校验与演化；阅读标题与目录项一致。 |
| [node-048](node-048.jpg) | 点击 concept-graph-query：图查询、邻域与推荐；阅读标题与目录项一致。 |
| [node-049](node-049.jpg) | 点击 concept-graph-3d：三维图谱、镜头与渲染生命周期；阅读标题与目录项一致。 |
| [node-050](node-050.jpg) | 点击 concept-graph-presentation：动态图谱与探索状态；阅读标题与目录项一致。 |
| [node-051](node-051.jpg) | 点击 concept-neo4j：Neo4j 属性图与 Cypher；阅读标题与目录项一致。 |
| [node-052](node-052.jpg) | 点击 concept-graphrag：GraphRAG 与边界；阅读标题与目录项一致。 |
| [node-053](node-053.jpg) | 点击 source-project：本项目源码；阅读标题与目录项一致。 |
| [node-054](node-054.jpg) | 点击 source-agent-book：ai-agent-book；阅读标题与目录项一致。 |
| [node-055](node-055.jpg) | 点击 source-shannon：Shannon；阅读标题与目录项一致。 |
| [node-056](node-056.jpg) | 点击 evidence-agent：单 Agent 核心代码；阅读标题与目录项一致。 |
| [node-057](node-057.jpg) | 点击 evidence-conversation：会话与记忆代码；阅读标题与目录项一致。 |
| [node-058](node-058.jpg) | 点击 evidence-planning：计划与依赖代码；阅读标题与目录项一致。 |
| [node-059](node-059.jpg) | 点击 evidence-execution：任务生命周期代码；阅读标题与目录项一致。 |
| [node-060](node-060.jpg) | 点击 evidence-runtime：执行运行时代码；阅读标题与目录项一致。 |
| [node-061](node-061.jpg) | 点击 evidence-orchestration：多 Agent 编排与 API；阅读标题与目录项一致。 |
| [node-062](node-062.jpg) | 点击 evidence-rag：RAG 与记忆代码；阅读标题与目录项一致。 |
| [node-063](node-063.jpg) | 点击 doc-roadmap：28 课完整路线；阅读标题与目录项一致。 |
| [node-064](node-064.jpg) | 点击 doc-graph-spec：知识图谱设计约定；阅读标题与目录项一致。 |
| [node-065](node-065.jpg) | 点击 doc-graph-practice：知识图谱建模与维护指南；阅读标题与目录项一致。 |
| [node-066](node-066.jpg) | 点击 doc-graph-3d：3D 星空图谱实现与操作；阅读标题与目录项一致。 |
| [node-067](node-067.jpg) | 点击 doc-graph-presentation：动态图谱实现与操作；阅读标题与目录项一致。 |
| [node-068](node-068.jpg) | 点击 doc-lesson-07：第七课任务包；阅读标题与目录项一致。 |
| [node-069](node-069.jpg) | 点击 doc-lesson-06：第六课验收记录；阅读标题与目录项一致。 |
| [node-070](node-070.jpg) | 点击 ref-book：ai-agent-book 固定版本；阅读标题与目录项一致。 |
| [node-071](node-071.jpg) | 点击 ref-shannon：Shannon 固定版本源码导读；阅读标题与目录项一致。 |
| [node-072](node-072.jpg) | 点击 concept-retry-budget：有限重试、抖动与共享预算；阅读标题与目录项一致。 |
| [node-073](node-073.jpg) | 点击 concept-recovery-fencing：持久状态、租约与迟到提交；阅读标题与目录项一致。 |
| [node-074](node-074.jpg) | 点击 evidence-ticket-reliability：工单幂等与有限重试源码；阅读标题与目录项一致。 |
| [node-075](node-075.jpg) | 点击 doc-lesson-08：第八课完整任务与源码导读；阅读标题与目录项一致。 |
| [node-076](node-076.jpg) | 点击 doc-lesson-08-acceptance：第八课受控验收与边界；阅读标题与目录项一致。 |
| [node-077](node-077.jpg) | 点击 doc-lesson-09：第九课持久化与恢复任务包；阅读标题与目录项一致。 |
| [node-078](node-078.jpg) | 点击 doc-graph-interaction-clarity：图谱邻居展开、曲线与文字清晰度；阅读标题与目录项一致。 |
| [node-079](node-079.jpg) | 点击 evidence-persistence：持久工单与恢复源码；阅读标题与目录项一致。 |
| [node-080](node-080.jpg) | 点击 doc-lesson-09-acceptance：第九课实现与证据边界；阅读标题与目录项一致。 |
| [node-081](node-081.jpg) | 点击 concept-temporal-workflow：持久化工作流、回放与 Activity；阅读标题与目录项一致。 |
| [node-082](node-082.jpg) | 点击 doc-lesson-10：第十课 Temporal 完整任务包；阅读标题与目录项一致。 |
| [node-083](node-083.jpg) | 点击 concept-context-budget：上下文预算与原文摘录；阅读标题与目录项一致。 |
| [node-084](node-084.jpg) | 点击 concept-index-lifecycle：文档版本与索引生命周期；阅读标题与目录项一致。 |
| [node-085](node-085.jpg) | 点击 evidence-context-assembly：上下文组装源码；阅读标题与目录项一致。 |
| [node-086](node-086.jpg) | 点击 doc-lesson-11：第十一课导读与验收；阅读标题与目录项一致。 |
| [node-087](node-087.jpg) | 点击 doc-lesson-12：第十二课完整任务包；阅读标题与目录项一致。 |
| [node-088](node-088.jpg) | 点击 evidence-knowledge-pg：PG知识接入与条件发布源码；阅读标题与目录项一致。 |
| [node-089](node-089.jpg) | 点击 doc-lesson-12-acceptance：第十二课实现与验收边界；阅读标题与目录项一致。 |
| [node-090](node-090.jpg) | 点击 doc-lesson-13：第十三课完整学习任务；阅读标题与目录项一致。 |

[打开缩略图画廊](gallery.html)

## 最终课程同步截图

- [lesson12-accepted-final](lesson12-accepted-final.jpg)
- [lesson12-english-final](lesson12-english-final.jpg)
- [lesson13-final-ready](lesson13-final-ready.jpg)

自动检查最终结果：106 项相关后端测试、9 项知识库前端测试、31 项图谱测试均通过；90 节点/127 关系校验及生产构建通过。
