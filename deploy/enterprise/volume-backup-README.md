# 会话文件卷备份与恢复（第24课）

会话正文保存在 `FileConversationRepository` 的根目录 UUID.json，正式归属保存在 PG `resource_owner`。仅恢复文件不能恢复租户可见性。运行人员必须停止应用写入，在同一个停写窗口完成 PG 和会话卷成对快照，并记录配对关系；本工具不会自动停止服务、自动删除卷，也不提供在线一致性快照。

## 使用

已验证固定 helper 镜像包含 GNU tar 1.35；必须使用 digest，不能使用可变化的 tag。在仓库根目录运行：

```powershell
node deploy/enterprise/volume-backup.mjs backup --source lesson24-runtime_conversations --image eclipse-temurin@sha256:541b08fc7f494edf14e436ff6bfb55767e4a115c87bfc547b8c5de957b58737f --output target/lesson24-conversations.tar
node deploy/enterprise/volume-backup.mjs restore --archive target/lesson24-conversations.tar --target lesson24-paired-recovery_conversations
node --test deploy/enterprise/volume-backup.test.mjs
```

源必须是显式命名、无额外 driver options 的本机 Docker local named volume，不能传宿主路径。备份拒绝挂载到运行容器的卷；恢复目标必须尚不存在（即使已有空卷也拒绝），且不能与源同名。创建新卷附加一次性 token，检查创建竞态是否复用了别人的卷，再检查运行挂载和空目录。操作期间应维持独占维护窗口，不能同时启动容器或启动另一位操作者的写入。

备份以二进制方式直接写入独占创建的 `target/*.tar`，不会经过字符串编码；同名文件和 manifest 均不可覆盖。manifest 包含归档 SHA-256、每文件 SHA-256、大小、UID/GID，不包含会话内容。失败保留归档、manifest 和已创建的目标卷，记录 failed，供人工检查；不会自动清理证据或覆盖重试。

## 验证边界

恢复仅接受受信备份流程生成的严格 USTAR 子集，先完整验证再调用 tar：总归档至多128 MiB、单文件至多8 MiB、至多10000个文件，只允许根目录 UUID.json（兼容 legacy 同名文件），JSON 内 id 必须匹配文件名。拒绝路径穿越、子目录、符号/硬链接、PAX/GNU 扩展、临时 .tmp、重复路径、异常校验和/填充、截断和非法尾部。归档首条必须是唯一的 `./` 根目录头，UID/GID 为10001且至少具有属主写入与执行权限，拒绝无根目录头、重复/延后根目录头或不可写根目录，避免恢复后目标根目录仍属于 root 而无法新建会话。文件 UID/GID 同样必须为10001，恢复保留数字属主。停写后仍有 .tmp 应先查明原因，不能当作有效快照。

SHA-256 用于损坏检测，并非签名：不能把攻击者提供的 tar 和 manifest 当作可信输入。该工具不接受通用业务归档，不提供导入第三方备份的功能。备份包含真实会话，必须保护访问权限、保留在忽略提交的 target 中，禁止提交归档和运行报告。

`complete` 代表归档已校验且 tar 命令成功，并不代替业务验收。恢复后需要在配对恢复的 PG 与新卷组成的独立应用中验证：文件 SHA/数字属主、会话正文与租户归属、其他租户不可读、应用重启后仍可读取，并保留原实例与原卷。Node 测试通过可注入 runner 验证拒绝运行中卷、拒绝已有目标、创建竞态、篡改、独占输出和失败不删卷；真实 Docker、PG 及页面证据须单独记录。

备份端128 MiB在tar结束后检查，是有效归档验收上限，不是流式磁盘配额；失败归档可能先写入更多字节。恢复端在读取前检查该上限。大卷应采用独立磁盘配额/流式限额方案，本教学工具不声称已覆盖容量保护。
