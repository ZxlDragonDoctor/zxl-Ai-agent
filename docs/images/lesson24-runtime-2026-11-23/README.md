# 第24课：低权限容器主线验收

## 产物与测试

JAR来自干净提交8965687，归档见[source-manifest.json](source-manifest.json)，JAR与实际容器字节摘要一致，见[build-runtime.json](build-runtime.json)。新前端独立npm ci及生产构建通过。最初两次测试准备失败：一次未提供PG变量，一次凭据文件相对路径错误；修正绝对路径后干净目录5项Java测试通过，失败/错误/跳过均0。它们是RuntimeSchemaPgTest及EnterpriseBootstrapTest，不将前批37项回归记为本批重跑。

新部署脚本在该产物基础上运行，完整deploy/enterprise Node测试20项通过，其中新运行身份/SQL策略8项。默认管理员迁移基线5215未被修改；独立lesson24-runtime项目使用新卷、新PG与5216端口。仅为验收另向loopback15495发布PG，覆盖文件和实际凭据全部保存在忽略的target目录。

## 实际启动与身份

PG健康→bootstrap管理员任务→migrate迁移角色→grant管理员任务→三业务Java服务。三运维任务退出0；main/worker/mcp实际环境为agent_runtime和validate，管理员/迁移密码未注入，见[runtime-identities.json](runtime-identities.json)。管理员仍存在于PG初始化及一次性运维容器中，不描述为完全移除管理员或完整密钥管理系统。

security/knowledge迁移历史installed_by及业务表owner都是agent_migrator，见[migration-identity.json](migration-identity.json)。实际运行账号可完成认证、业务数据和预算写入，同时CREATE TABLE、ALTER TABLE、CREATE TEMP、历史DELETE、SET ROLE agent_migrator均返回42501，见[permissions.json](permissions.json)。这不是RLS或按服务拆分DB身份。

## 真实模型与恢复

独立新租户员工认证→规划→Research→实际MCP空检索→正确拒答→清理完成。usage1297+1332=2629；run/period spent均2629、reserved0、calls2，工单写入0。[HTTP观察](http-usage.json)。这是空库拒答单例，不推广为带引用回答、审批、质量评估或压测通过。

有序重启脚本实际退出0，原completed报告可读、cleanupPending=false，MCP维护账号刷新后READY；重复up后再次核实，没有重复请求模型。[报告读回](restart-observation.json) · [账本读回](restart-ledger.json)。本次没有重启PG进程、删除卷或演练备份恢复。

## 权限漂移与事务故障

审查发现旧角色的直接ACL可能漏过，修复后真实GRANT CREATE/TEMP再重跑bootstrap/grant，两项权限从true收敛为false：[注入前](acl-drift-before.json) · [收敛后](acl-drift-after.json)。再分别注入REPLICATION、已知空测试表所有权，bootstrap/grant都拒绝；测试状态已恢复，测试表已删除。[拒绝记录](policy-drift-denials.json)。未实测所有schema/function/type所有权组合，不能声称完整漂移矩阵。

bootstrap/grant各自使用显式事务。在全部表DML授权后、撤销历史DML前注入除零错误，任务失败后历史DELETE仍为false，随后正常两任务重跑退出0，见[事务回滚](grant-rollback.json)。跨进程的bootstrap/migrate/grant仍不是单一事务。

加固后的最终脚本又在第二个全新PG卷上执行bootstrap/migrate/grant，三任务全部退出0，版本9/3且runtime无TEMP/CREATE，见[fresh-init.json](fresh-init.json)。第二组只验初始化，未启动业务或调用模型；取证后停止其PG容器保留卷，原5216运行组未受影响。

源码里新增部署目录.gitattributes固定shell为LF，git check-attr确认两个shell入口为lf，避免Windows检出导致Linux启动错误。

## 页面与未完成项

容器5216实际操作登录门→员工登录→课程中文卡→英文2D→英文3D，截图04–08。当前工作区前端的更新知识节点、源码关联及语言切换另记；前端产物的源码版本分别标注，不把工作区页面冒充干净8965687产物。个人掌握与笔记未修改。

截图09–11来自当前前端5212中的更新schema概念、英文2D和关联源码；37项图谱回归及生产构建通过。这些知识更新属于当前工作区内容，未冒充8965687的旧前端产物。

仍待：TLS、备份恢复、应用回滚及schema兼容性、完整双租户审批业务、持续交付检查和生产容量。本课保持进行中。

[学习导读](../../learning/24-runtime-identity-containers.md) · [可复用配置](../../../deploy/enterprise/runtime-README.md)
