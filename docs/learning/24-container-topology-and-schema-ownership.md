# 第24课：容器网络与迁移身份

同一个JAR能启动不等于部署拓扑正确。本项目内部客户端要求认证的loopback来源，因此本批让主应用持有网络命名空间，Worker、MCP、nginx加入该空间。数据库是独立服务，前端只发布本机HTTP端口；不会把Worker/MCP直接暴露为宿主端口。

## 为什么不能并行重启

实际执行并行restart时，Worker/MCP曾因主容器暂未运行而无法加入命名空间。修复不是删除数据卷，而是先停依赖容器，再重启主应用并等待内部HTTP200，然后启动依赖容器，最后核实宿主HTTP及真实工具发现。

```sh
node deploy/enterprise/restart-apps.mjs --project lesson24-enterprise --env-file /absolute/private.env --compose deploy/enterprise/compose.json --origin http://127.0.0.1:5215
```

命令必须带原部署的全部Compose覆盖文件。脚本不会删卷、重建镜像或重启PG；它只证明主应用和代理HTTP就绪，工具发现和业务报告必须另验。这个同主机部署组不是独立横向扩展或滚动高可用方案。

## 迁移身份与运行身份

开发时自动迁移很方便，但让长期运行账号持有建表、改表权限不是唯一选择。新增共享 `DatabaseSchemaLifecycle`，SecurityDatabase、KnowledgeDatabase和课堂LessonDatabase统一使用它：

| 配置 | 行为 |
| --- | --- |
| AGENT_DB_SCHEMA_MODE=migrate | 默认兼容原行为，启动时执行迁移 |
| AGENT_DB_SCHEMA_MODE=validate | 只校验历史、校验和、pending及future版本，不创建schema、不迁移；不符合即拒绝启动 |

validate必须由部署者显式开启，当前enterprise profile不会偷偷将默认值改掉。运行账号只需schema使用权、业务表DML及必要sequence权限；Flyway历史只授予SELECT。应用共享数据库账号并不等于PG RLS租户隔离。迁移历史一致也不能检测任意手工物理列变更。

独立迁移入口 `com.zxl.zxlaiagent.persistence.SchemaMigrator` 接受显式模块 `security knowledge lesson9`，只使用该进程环境中的LESSON12_DB_*迁移身份。完成迁移及授权后，运行服务改用另一组凭据和validate模式；不要把迁移凭据留在运行容器里。pgvector扩展安装仍需有能力的运维身份先完成，不能把运行账号提升为superuser来绕过错误。

未来版本会被validate拒绝，因此不能把旧应用放回新数据库就宣称回滚成功。兼容性、备份和回退路径仍需独立演练。

## 本批证据与边界

容器使用已验收4afd70f JAR，不含本批新schema模式代码。实际链路结算2619 token，并行重启失败后已恢复；有序脚本再次实跑通过，原报告可读，工具发现恢复。Java UID10001、JAR0444和固定镜像摘要已核实。

新schema模式通过真实PG受限角色专项及相关回归：运行DML和pgvector查询可用，CREATE/ALTER和修改迁移历史被拒绝；缺V9、未来V99在security schema上拒绝。原知识库和课堂持久化默认行为回归通过。独立迁移CLI已在另一新库实跑security/knowledge；lesson9 CLI未单独执行。

当前Compose仍使用初始迁移账号，不能据此声称容器运行账号已最小化。后续须构建包含新schema模式的干净产物、分离容器凭据、验证完整HTTP和重启后再更新此边界。

[容器用法](../../deploy/enterprise/README.md) · [原始观察与截图](../images/lesson24-containers-2026-11-23/README.md)
