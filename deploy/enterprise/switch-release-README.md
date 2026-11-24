# 第24课：受检查的应用发布与回退

应用版本回退与数据库恢复是两件事。此工具只切换本机专用 Compose 运行组的四个应用容器，不迁移、删除或恢复 PostgreSQL 和会话卷。操作者应先确认无进行中运行和审批写入，并保留可恢复备份，在独占维护窗口执行；工具不会自动排空业务，也不是滚动高可用发布。

每个受信发布清单只含以下字段，镜像必须是已经存在的本地完整 image ID（不可变的 `sha256:` 加64位摘要），不接受可变tag：

```json
{
  "releaseId": "release-a",
  "javaImage": "sha256:<64 lowercase hex digits>",
  "frontendImage": "sha256:<64 lowercase hex digits>",
  "conversationFormat": "uuid-json-v1",
  "schemaModules": ["security", "knowledge"]
}
```

JSON中的占位符必须替换为实际 `docker image inspect --format '{{.Id}}' <image>` 的值。清单不保存配置、Cookie或密码；镜像ID不能替代可信构建来源和依赖审查。会话格式字段只是显式兼容约定，不是自动证明任意业务数据语义兼容。

```powershell
node deploy/enterprise/switch-release.mjs --project lesson24-paired-recovery --env-file target/private.env --compose deploy/enterprise/compose-recovery.json --compose target/original-volume-override.json --origin http://127.0.0.1:5218 --current target/release-a.json --candidate target/release-b.json --output target/release-a-to-b.json
```

必须提供该组所有实际Compose文件；不可丢失已有卷、网络和环境覆盖。当前四容器须运行且镜像与current清单一致。工具核对main配置和实际80/tcp发布端口均指向origin，所有Java角色必须显式validate。随后生成只含候选镜像的覆盖文件，使用候选JAR的 `SchemaVerifier security knowledge` 及现有运行身份检查schema历史/校验和。检查失败在停服务前退出，不自动跑迁移。没有SchemaVerifier的旧镜像不能冒充已检查版本。

检查通过后停止四个应用角色，先强制重建main并轮询内部HTTP200，再重建共享main命名空间的worker/mcp/frontend，轮询对应宿主HTTP并再次核对四个实际镜像和main端口。容器操作120秒超时，readiness各最多30次（内部命令也受子进程超时限制）。成功状态`ready`只代表这些检查，不代表模型、工具、权限及业务全部验收。

输出报告和同名`.compose.json`覆盖文件限定仓库target目录的新文件，不覆盖历史证据。报告不保存展开的Compose配置；命令输出与错误经过固定码处理。**后续重启应继续传入本次生成的覆盖文件**，否则原环境中的旧镜像配置会重新生效。再次切换时，current必须改为已运行的发布清单；新候选覆盖放在最后。

失败记录阶段与固定码并保留现场，不自动重试或回退，不删除卷或PG。部分切换失败可能留下停止或混合容器，需要操作者按报告检查实际镜像与数据，再用保留的覆盖文件有序恢复；当前要求所有四角色运行的切换入口不是任意故障状态的自动修复器。

回退使用相同命令，current指向B、candidate指向A，换一个新output。schema检查不等于业务兼容：还须用真实身份读历史消息/报告和预算，验证可继续写入，并实际截图。数据库若已升级导致旧JAR有pending/future/checksum冲突，必须阻止直接回退；本工具不提供降级迁移或自动数据库还原。

`node --test deploy/enterprise/switch-release.test.mjs`覆盖schema/当前版本/validate配置/端口错误的停机前拒绝、顺序重建、有限等待、失败证据与禁止覆盖。Docker和页面演练另列证据，不能用注入runner测试代替。

外层子进程超时只保证本工具停止等待，不证明Docker服务端的one-shot容器或数据库查询已经取消。失败后检查维护组是否有残留run容器/连接，再由操作者处理；本工具不把未知状态误写为ready。
