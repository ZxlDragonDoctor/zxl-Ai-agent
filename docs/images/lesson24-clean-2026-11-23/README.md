# 第24课：干净构建与真实启动验收

本批已实际使用提交 `4afd70f` 的干净源码构建并启动，不复制工作区未提交修改、application-local.yml或历史class。模型密钥只通过显式环境注入，不放入源码归档或证据。

| 层级 | 实际观察 |
| --- | --- |
| 源码 | 导出工具固定提交，ZIP SHA256及省略旧图片MCP子模块的固定版本见[source-manifest.json](source-manifest.json) |
| 后端 | 干净目录执行EnterpriseBootstrapTest并package，4项通过，错误/失败/跳过均0 |
| 前端 | 干净目录npm ci --ignore-scripts及生产构建通过；既有大分包提示仍在 |
| 启动 | 同一fat JAR通过PropertiesLauncher启动企业主应用18401、Worker18432、MCP18433 |
| 新库 | 在既有PG16.15实例中创建隔离空数据库，实际应用9项lesson17迁移至V9；后续启动确认无重复迁移 |
| 真实业务链 | 新租户员工认证、规划、Research、MCP空检索、正确拒答；真实qwen-plus usage1133+937=2070；双层reserved0、spent2070、calls2，工单写入0 |
| 重启 | 重启主应用，重新登录读取原completed报告、cleanupPending=false；独立PG再次读取双层spent2070，未重新调用模型 |
| 通用启动器 | 后续使用launch.mjs再次启动同一JAR的main角色，重新认证读回报告；维护账号刷新MCP发现后READY。启动器与预检合计15项Node测试通过 |

[最终产物与运行观察](final-build-runtime.json) · [HTTP观察](http-usage.json) · [重启报告读取](restart-observation.json) · [重启账本](restart-ledger.json)

## 保留失败与范围

最初`89f48ea`干净构建成功，见[初始构建观察](build-observation.json)，但不作为修复后启动证据。离线npm安装因缺tarball缓存失败，普通npm ci随后成功；不声称离线交付完成。

新企业入口首次完整启动曾因Skill配置缺Gateway独立Bean失败，实际依赖图刷新测试同样复现。修复三个拒绝型兼容Bean后，4项测试、最终干净构建及真实启动均通过。失败发生时数据库已迁移V9，不能因启动失败就认定迁移没有发生。

导出工具8项临时Git仓库测试通过；凭据名称与链接检查只是明确的源码导出规则，不是内容密钥扫描器。省略旧图片MCP子模块不代表该模块已构建或验收。

Docker引擎不可连接，没有容器运行证据；数据库是新建database，宿主PG进程与运行时依赖来自本机，不是全新操作系统。未验收最小数据库权限、TLS、备份恢复、数据库进程重启、应用回滚、跨租户完整审批链及生产容量。缺少Validation provider的启动提示仍存在，本批不将其写成所有请求校验均已覆盖。

## 浏览器逐状态

初始5213为89f48ea干净前端对既有后端的课程页面，截图01–03仅证明静态构建。最终5214为4afd70f干净前端代理18401新主应用：截图04登录门、05实际员工登录后的页面、06课程中文阅读卡、07英文2D、08英文3D；连续操作通过，个人学习进度未修改。HTTP业务结果由独立脚本及数据库核实，不将课程阅读截图当作工单审批页面验收。

截图09–11是当前工作区前端构建中的更新课程知识（5212），不冒充4afd70f的干净前端产物。该知识变更37项图谱回归与生产构建通过。main重启后MCP发现元数据需维护账号显式刷新，页面初始显示未发现工具不等于Worker/MCP已停止；本批已完成刷新再复看页面。

[课程导读](../../learning/24-clean-source-and-enterprise-entry.md) · [完整课程任务](../../learning/24-reproducible-deployment.md)
