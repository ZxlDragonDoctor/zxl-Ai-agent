# 第十七课实战：把旧会话变成只读归档

旧系统只有会话 JSON，没有可信归属。文件名是资源标识，不能证明当前登录者有权读取。迁移需要先回答两个问题：管理员明确授权了哪些历史？后来读到的还是审批时那份内容吗？

本项目保留原文件，用 PostgreSQL 记归属、导入清单摘要和逐文件 SHA-256。可以把它理解成一个 Spring Boot 下载接口：先从登录会话获得主体，查数据库确认归属，再校验允许读取的文件。知道 UUID 不会绕过这三步。

| 组件 | 要读的工程动作 |
|---|---|
| [清单](../../src/main/java/com/zxl/zxlaiagent/security/LegacyHistoryManifest.java) | 每次只认管理员指定的租户、账号和文件；限制数量，拒绝重复 ID。 |
| [文件校验](../../src/main/java/com/zxl/zxlaiagent/conversation/repository/LegacyConversationFiles.java) | 只读规范 UUID 文件，拒绝符号链接，限制 8 MiB；从同一份字节算摘要并解码。 |
| [导入](../../src/main/java/com/zxl/zxlaiagent/security/LegacyHistoryImporter.java) | 先验证备份，再把 owner、收据和明细写入同一 PG 事务；重复执行同一份清单不覆盖归属。 |
| [读取](../../src/main/java/com/zxl/zxlaiagent/conversation/service/ConversationService.java) | 正式模式下先确认归属和收据，再读归档；校验失败拒绝读取。旧运行不恢复、不重放工具。 |
| [页面](../../zxl-Ai-agent-fronted/src/views/AgentChat.vue) | 统一对话页查看历史和工具证据。未结束的归档不计时；可以另建普通会话。 |

## 本地练习

1. 使用合成历史，在 PostgreSQL 中预先创建独立的 `employee` 验收账号，参照[离线账号创建](17-account-provisioning.md)。正式服务须设置 `agent.security.mode=required`；教学模式没有这些授权约束，使用独立存储目录。
2. 选中要导入的 UUID 文件，用 `(Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()` 获得摘要。用 UTF-8 保存清单，填真实存在的专用账号的租户和主体 ID。以下占位值需要替换：

   ```json
   {
     "importId": "621eb584-63a1-4655-88ae-c97b084c53d1",
     "tenantId": "legacy-lab",
     "subjectId": "legacy-reader",
     "entries": [
       {
         "conversationId": "cda765b8-ea3c-4d44-a453-2c8cbe17f25f",
         "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
       }
     ]
   }
   ```

3. 两个目录都应预先存在、互不包含，并限制本地访问。正式应用读取的存储目录须与导入源一致。暂停外部脚本对导入源的写入。使用本地 PG 配置，编译并生成运行依赖列表，再启动离线命令：

   ```powershell
   mvn.cmd -q -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/lesson17-classpath.txt"
   $archiveClasspath = 'target/classes;' + (Get-Content target/lesson17-classpath.txt -Raw).Trim()
   java -cp $archiveClasspath com.zxl.zxlaiagent.security.ImportLegacyHistoryCommand `
     --manifest $manifestFile --source-dir $sourceDir --backup-dir $backupDir
   ```

4. 首次应返回 `IMPORTED`；再次使用字节完全一致的清单应返回 `ALREADY_IMPORTED`。用专用账号打开统一对话页，查看历史工具明细，再换一个账号验证列表无归档、直连详情返回 404。按需验证归档的发送、删除和上下文预演接口返回 `ARCHIVE_READ_ONLY`。

文件与数据库不是一个事务。备份失败不会产生新归属；数据库拒绝后，已经写好的备份可能保留。保留原清单和备份、核对原因后再执行相同清单。备份目录需要和源数据同等级的访问保护，不能提交到 Git。若源文件被改动，详情拒绝显示，列表略过这一条以保留普通会话。运维可用原清单和备份核验并恢复原字节。当前没有在线导入或恢复工具；真实旧文件在管理员明确授权前继续保持原状。

面试练习：如果备份成功但数据库事务回滚，如何安全重试？为什么要保留旧 `running` 的事实，却不把它交给新执行器？请用当前项目的具体事务和权限入口回答。
