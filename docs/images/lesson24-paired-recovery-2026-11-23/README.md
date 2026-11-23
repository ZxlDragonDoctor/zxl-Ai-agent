# 第24课：会话卷与PG成对恢复验收（2026-11-23）

## 环境与范围

源运行组 lesson24-runtime（5216），恢复组 lesson24-paired-recovery（5218），原PG-only恢复组5217保留。应用镜像仍为上批干净提交8965687构建的同一JAR及前端，本批新增备份工具不改变业务JAR。更新后图谱单独在5212由当前工作区生产构建预览；不将它误称为干净镜像更新。

会话内容是专门创建的合成演练数据；无实际用户历史被迁移或删除。先完成知识助手测试运行（两条消息、一次completed运行、busy=false），然后停止源main/worker/mcp/frontend，PG保留运行。该独占维护窗口中连续生成PG custom归档和文件卷USTAR。两个归档及私密配置仅保存在忽略提交的target，不提交正文、密码、Cookie或二进制归档。

本批回答路径使用demo-literal-containment-v1证据不足拒答，验证持久化和恢复，不声称新增真实回答模型验收。此前真实模型/向量证据见上一单元记录。

## 实际验证

- PostgreSQL恢复到另一容器的新库agent_pair_recovered；角色先准备且未预建业务schema。所选12表的行数和逻辑摘要一致，见source/restored-fingerprint.json，排除不断变化的登录/权限审计。
- 文件恢复到新卷lesson24-paired-recovery_conversations；唯一UUID文件3486字节，SHA256与源manifest一致，UID/GID10001。API读回原消息与运行详情摘要一致，见paired-observation.json。
- 目标开放前撤销10条恢复的活跃登录会话，保留审计关联行。新登录后列表和详情可读。同租户另一主体列表不含该会话、详情404，见ownership-denial.json。此项不是新增双租户全业务测试。
- 恢复后经正常API新建并写入另一测试对话，两条消息和completed运行成功；随后只删除新建的临时对话。原恢复样本保留。
- 恢复组有序重启后新登录再次读取原样本，仍为相同详情摘要。MCP发现刷新READY；这不是全工具或新模型调用证明。
- 源组恢复运行；仅通过正常API删除源端本批创建的演练对话，其他数据和既有运行组保留。
- 运行中源卷备份实际返回VOLUME_IN_USE且未创建归档；源卷同名恢复实际返回SOURCE_TARGET_SAME，已有未挂载目标返回TARGET_VOLUME_EXISTS（见rejection-observation.json）。最终工具版本另恢复到全新验证卷成功。已有目标、篡改、路径/链接、临时文件、无根目录头及根权限不足由自动测试覆盖。

## 自动化与构建

`node --test deploy/enterprise/*.test.mjs`：43/43通过，其中卷工具12项。审查发现无根目录头可能导致恢复后不可写，先新增失败用例，再修复并重跑。备份容量上限为完成后校验，不声称流式磁盘配额。

`npm --prefix zxl-Ai-agent-fronted run test:graph`：37/37通过。生产构建通过，有已有大chunk提示。本批没有Java逻辑变更，未新增Maven或真实回答模型执行主张。

## 逐状态页面操作

截图来自无个人持久配置的独立Chrome上下文；每次点击后等待会话加载结束。第一次尝试截图发现加载中状态混入，已补等待并重跑全部对话操作，保留最终截图。

| 操作 | 预期及实际结果 | 截图 |
| --- | --- | --- |
| 打开恢复组chat-room | 要求重新登录，不复用备份登录态 | 01-fresh-login.png |
| 员工登录 | 历史列表显示恢复的演练对话 | 02-restored-history.png |
| 选择历史 | 显示原问题、拒答、completed运行 | 03-restored-messages-zh.png |
| 展开思考/执行记录 | 可读恢复的执行与上下文记录 | 06-restored-execution-record.png |
| 切换英文 | 导航和控件切换，原始消息保留 | 04-restored-messages-en.png |
| 刷新页面后重选 | 消息仍存在，不触发新生成 | 05-reloaded-history.png |
| 打开备份概念阅读卡 | 显示PG与文件卷成对恢复的新知识 | 07-backup-concept-zh.png |
| 切换英文、2D | 阅读卡与关系持续存在 | 08-backup-concept-en.png、09-backup-concept-en-2d.png |
| 点击卷工具证据关系 | 路径指向volume-backup.mjs | 10-backup-source-en.png |
| 切回3D | 新工具阅读卡保持，场景节点可见 | 11-backup-source-en-3d.png |
| 搜索“会话文件卷”并筛选证据类型 | 找到唯一卷工具节点，点击后显示源码路径 | 12-filter-volume-evidence.png |

## 尚未覆盖

手工停写并非在线分布式一致性快照，不覆盖外部操作者并发写入。归档摘要不是来源签名；工具仅接受受信归档，不是任意tar导入器。浏览器个人学习进度不在服务端备份中且未改为已掌握。WAL/PITR、外部TLS、应用版本回滚、跨租户完整审批、容量与持续交付仍按后续单元推进；第24课保持进行中。
