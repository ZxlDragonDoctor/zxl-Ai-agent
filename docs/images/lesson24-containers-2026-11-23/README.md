# 第24课：容器与数据库运行身份

## 实际容器链路

Docker Desktop引擎29.7.2已实际启动。基础镜像按实际拉取记录的摘要构建，见[images.json](images.json)，没有编造摘要。PG容器实际16.16，pgvector版本见[database-version.json](database-version.json)，与宿主PG16.15测试环境分别记录。

实际pgvector版本0.8.7，按应用使用的lesson12,public搜索路径查询余弦距离为0，schema版本security9、knowledge3，见[schema-vector-check.json](schema-vector-check.json)。默认public搜索路径的首个探针曾提示vector类型不存在，修正探针搜索路径后通过，不将其误写成扩展缺失或完整检索评估。

Java镜像使用前批干净4afd70f JAR，SHA256及实际镜像ID见[runtime-images.json](runtime-images.json)。同一镜像运行main/worker/mcp，UID10001，JAR模式0444；nginx共享网络命名空间并保留Host。主配置仅发布本机5215:80；本次另用target中的验收覆盖文件只向127.0.0.1:15494发布PG，供独立psql核对，不将覆盖文件或凭据提交。

新PG命名卷、会话命名卷实际创建。主应用、Worker、MCP、nginx及PG五容器均启动，认证接口经nginx返回200。实际镜像缺配置返回DB_URL_REQUIRED、未知角色返回ROLE_INVALID，见[入口拒绝观察](entrypoint-rejections.json)。镜像构建和运行都不是Node结构测试的替代描述。

真实员工认证→多Agent规划→Research→MCP空检索→正确拒答，usage1269+1350=2619 token；run/period spent均2619、reserved0、calls2，工单写入0。[HTTP观察](http-usage.json)。这是空知识库单例，不代表有引用回答或审批写入已经通过容器验收。

## 实际重启失败与修复

并行compose restart main worker mcp frontend退出1：依赖容器无法加入暂时停止的主容器网络命名空间。主应用和PG恢复运行，但worker/mcp退出143；没有删除卷或重建数据库。

按“停依赖容器→重启main→内部HTTP200→启动依赖容器→宿主HTTP200”手动恢复后，新增有序脚本又实际执行一次并退出0。随后重新认证读取原completed报告、cleanupPending=false；维护账号刷新工具发现恢复READY，独立PG读回双层spent2619。[重启报告](restart-observation.json) · [重启账本](restart-ledger.json)。没有重新提交模型请求。

## 新数据库身份分离代码

本批新代码引入显式validate模式及独立SchemaMigrator，**尚未进入上述4afd70f容器JAR**。最终7类37项Java专项及相关回归全部通过，见[schema-tests.json](schema-tests.json)。其中知识库/运行角色/连接池测试使用真实宿主PG，课堂持久化对照用H2；不将37项全部称为PG测试。

受限PG运行角色可DML和执行pgvector查询，CREATE/ALTER/修改迁移历史拒绝；缺V9和未来V99均拒绝启动。异常注入仅security schema实测，不声称三套schema完整异常矩阵。独立迁移CLI实际在另一空库执行security和knowledge成功；lesson9 CLI未单独运行。校验只针对迁移历史，不检测任意物理列漂移，也不是PG RLS租户隔离。

当前Compose仍使用初始迁移身份，不能写成容器账号已经最小化。尚待新干净JAR、运行/迁移容器凭据分离、TLS、备份恢复、PG容器进程重启、回滚与完整双租户审批业务。

## 自动化与页面

容器配置/暂存5项、有序恢复4项及路径校验3项Node测试共12项通过。新增路径校验先失败再实现，Windows大小写与父junction可导致的自身递归复制已在写入前拒绝，未执行旧危险递归。独立复核通过。页面实际在容器前端5215完成登录、中文课程卡、英文2D与3D连续切换，截图04–08。认证截图不替代真实模型和数据库的独立观察。

截图09–11为当前工作区前端5212中的新schema知识节点、英文2D与关联源码，非容器4afd70f旧前端。37项图谱回归及前端生产构建通过；个人掌握、笔记未修改。

[课程知识](../../learning/24-container-topology-and-schema-ownership.md) · [容器运行说明](../../../deploy/enterprise/README.md)
