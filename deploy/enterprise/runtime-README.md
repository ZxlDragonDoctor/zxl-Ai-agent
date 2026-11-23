# 独立运行身份容器拓扑

`compose-runtime.json`是单独完整拓扑，不覆盖旧compose.json。使用新的项目名、卷、前端端口5216（FRONTEND_PORT可覆盖）和新构建的镜像，不能给旧4afd70f镜像设置validate后声称模式生效。

额外显式环境：ENTERPRISE_RUNTIME_IMAGE、ENTERPRISE_RUNTIME_FRONTEND_IMAGE、MIGRATOR_DB_PASSWORD、RUNTIME_DB_PASSWORD；其余基础镜像、管理员PG、工具令牌和模型配置沿用部署说明。运行、迁移和管理员密码须不同。不要打印展开后的Compose配置；使用config --quiet。实际值只保存在私有环境文件中。

生命周期：PG健康 → bootstrap管理员建固定agent_migrator/agent_runtime账号、migrator所有的lesson12/17 schema与vector扩展 → migrate仅执行security/knowledge → grant管理员授予运行账号业务DML、sequence及迁移历史SELECT → main/worker/mcp使用AGENT_DB_SCHEMA_MODE=validate。运行服务环境中没有管理员或迁移账号密码；migrate没有内部令牌或模型密钥。

管理员仍存在于PG初始化和两项一次性运维服务中；不是完全消除管理员。后续可停止已完成运维容器，切勿删除卷。该账号模型是应用共享DB运行身份，不是PG RLS租户隔离，也没有进一步拆分main与worker权限。

bootstrap面向专用新PG部署组。重复执行会复用已存在且符合无高权限（含REPLICATION）/无角色成员关系约束的固定角色，不自动旋转密码；配置中更换既有角色密码会使后续登录失败，须单独实施受审密码轮换。schema若已存在但所有者不符合预期，迁移应失败，不自动接管别的应用schema。grant会对两套schema业务表授予DML，再撤销历史写入；每次新增迁移后重新运行grant。暂不授未来表的默认权限。

bootstrap和grant均先拒绝运行角色拥有当前数据库或任何非系统schema、relation（含表/sequence）、function、type的情况，不自动改所有权。仅直接ACL漂移时，两者会显式撤销PUBLIC和运行角色在lesson12/17/public的CREATE及数据库CREATE/TEMP，并用has_schema_privilege、has_database_privilege再次核实。角色标志或所有权漂移不会靠REVOKE掩盖；须单独审查。此检查不是任意SECURITY DEFINER函数或所有扩展的完整安全审计。

两套SQL分别使用显式BEGIN/COMMIT事务包住全部角色/schema/授权操作；密码环境变量读取在事务前完成。grant对业务表整体授权再撤销历史写入在同一事务提交，其他连接不会观察到中间的历史写权限。ON_ERROR_STOP遇错退出并关闭连接，未提交事务全部回滚，不留下半笔ACL修正。两项服务之间仍是分开的运维事务，不能将bootstrap与后续Java迁移和grant误称为跨进程单一事务。

命令示例：

```sh
docker compose --project-name lesson24-runtime --env-file /absolute/private.env -f deploy/enterprise/compose-runtime.json config --quiet
docker compose --project-name lesson24-runtime --env-file /absolute/private.env -f deploy/enterprise/compose-runtime.json up -d --no-build
```

镜像需父流程用已固定源码/校验值的JAR和dist预先构建，不在Compose运行阶段构建。需要外部psql验收时使用单独私有覆盖文件发布PG至loopback，主拓扑默认不发布PG。共享网络命名空间的重启仍使用restart-apps.mjs并传本文件，不能并行restart全组。

8项Node测试只是环境隔离、阶段依赖及SQL授权契约，不代替真实PG/容器验证。须实际核实角色标志、DDL/history写拒绝、业务HTTP与结算、首次与重复迁移、重启后读回。未实测前不将本文件标为课程完成。
