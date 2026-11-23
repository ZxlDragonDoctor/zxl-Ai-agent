# 第24课：PostgreSQL逻辑备份恢复验收

本批没有修改Java业务代码，不把前批Java测试数量重复计入。备份/恢复与恢复启动配置新增11项Node测试，整个deploy/enterprise共31项Node测试通过，失败/跳过0。注入runner测试与下面真实Docker/PG观察分开，不用单测代替恢复验收。

## 实际数据与归档

源组lesson24-runtime保持运行。先通过维护账号导入隔离演练文档，真实text-embedding-v3生成1个1024维分块，见[导入观察](import-observation.json)。此前2629 token对话模型记录作为要恢复的历史运行，不重新执行对话模型。

custom二进制通过文件描述符备份到忽略的target；归档不提交Git，manifest见[archive-manifest.json](archive-manifest.json)。SHA256只证明字节一致，不代表归档可信或加密。归档包含用户密码哈希、会话等敏感数据，须按敏感备份管理。

源/目标Docker固定ID不同。目标全新PG先只准备角色名及新密码，不创建业务schema，工具创建agent_recovered并完整单事务恢复，保留owner/ACL。[restore-manifest.json](restore-manifest.json)。源/目标使用已固定的同一PG镜像基线；未验证跨PG大版本恢复。

## 逻辑数据匹配

[源快照](source-fingerprint.json)与[目标快照](restored-fingerprint.json)选定12张稳定业务表，行数与有序JSON逻辑MD5全部一致，见[比较](fingerprint-comparison.json)。包括运行事件、预算三表、知识原文/版本/分块；审批样本为空，不宣称审批数据恢复已实测。会话与持续变化的权限审计不属于摘要比较，不能称为整个DB逐字节一致。

[恢复结构与向量](restored-schema-vector.json)确认security9、knowledge3、1024维向量及owner agent_migrator。新登录读回原completed报告、cleanupPending=false，见[报告](restored-report.json)；账本仍为2629、reserved0、calls2，见[账本](restored-ledger.json)。真实新查询Embedding检索命中恢复文档，见[知识读回](knowledge-recovery.json)，没有重新发起对话模型调用。

## 身份、拒绝与源保护

恢复后明确撤销目标DB默认CREATE/TEMP，开放应用前将10条恢复会话全部撤销，见[会话策略](restore-session-policy.json)。审计关联行保留，验证使用新登录。运行身份的5类权限拒绝仍通过，见[权限](permissions.json)。全局角色密码不在dump中，目标使用另行配置的新密码。

真实重复恢复被TARGET_DATABASE_EXISTS拒绝，源名称/ID别名被SOURCE_TARGET_SAME拒绝且未创建源端探针库：[拒绝观察](rejection-observation.json) · [别名拒绝](source-alias-rejection.json)。源业务快照在恢复后仍匹配，见[source-after-restore.json](source-after-restore.json)。随后按正常API清理源端演练文档，状态DELETED、chunkCount0，见[源清理](source-cleanup.json)；历史版本和审计按业务规则保留。恢复端样本仍ACTIVE，用来展示备份时状态。

## 应用与页面

恢复组使用独立compose-recovery.json，无bootstrap/migrate/grant任务；三角色validate明确连接agent_recovered，见[实际身份](restored-runtime.json)。核对原PG卷lesson24-recovery_postgres后，有序重启脚本退出0，新登录报告、知识检索仍可用。PG未额外向宿主发布端口；早先宿主端口权限探针失败后改为容器内loopback TCP，实际密码认证和权限探针通过，不把失败算通过。

页面5217实际操作：登录→维护账号文档清单ACTIVE→分块→完整原文→真实检索→英文，截图01–06。没有拍摄填写密码的状态。新学习图谱的当前前端页面另记，个人进度和笔记未改。

截图07–11为当前工作区前端5212的备份知识节点：中文、英文、2D、关联源码、回到3D；阅读卡与源码位置保留。37项图谱回归及生产构建通过；它不是8965687旧前端产物的截图。

## 未覆盖

没有恢复会话文件卷、浏览器学习进度、外部模型配置或角色全局密码。没有验证活动中的DAG恢复、UNKNOWN预算样本、WAL/PITR、跨版本升级、TLS、应用回滚、完整双租户审批或生产恢复时长/恢复点目标。目标会话文件卷是新卷，本次源测试没有会话文件恢复样本。第24课仍进行中。

[学习导读](../../learning/24-postgresql-backup-and-recovery.md) · [工具操作](../../../deploy/enterprise/backup-README.md)
