# 企业部署专用入口

`com.zxl.enterprise.EnterpriseAgentApplication` 是独立正式入口，保留原 `ZxlAiAgentApplication` 及默认打包入口不变。以 `--spring.profiles.active=enterprise` 启动；混用 local、课堂或其他 profile，以及将身份模式覆盖为 legacy-demo，都会在创建业务 Bean 前拒绝。

先按 `preflight.mjs --role main` 检查显式环境，再设置真实 PostgreSQL、内部服务令牌、模型密钥、Worker/MCP loopback 来源及独立会话目录。当前服务要求同主机 loopback，不能直接宣称支持 Compose 跨容器服务名。

源码构建后的类路径启动示例（PowerShell，依赖类路径由 Maven dependency:build-classpath 生成到 target 文件）：

```powershell
$dependencyPath = (Get-Content target/dependency-classpath.txt -Raw).Trim()
java -cp "target/classes;$dependencyPath" com.zxl.enterprise.EnterpriseAgentApplication --spring.profiles.active=enterprise
```

不要把旧验收目录的 class、application-local.yml 或私有凭据复制进新构建。`mvn package` 仍将旧入口固定在 manifest；本课新入口使用显式类路径，不把两种启动方式混淆。

入口仅扫描现代 conversation、multiagent、security、skills、usage、research、ticket、knowledge.gateway 与 agent.stream 模块，排除旧 ResearchConfiguration。不会创建 LoveApp、本地 RAG 索引、旧 ToolRegistration 或旧 Controller。Provider 注册表中的正式 Research、Knowledge、Skill 和审批能力继续由原正式模块提供。

为兼容原构造器，allTools 是空数组；未授权的旧 ResearchRunner 所有操作明确拒绝。正式 Research 仍通过 ScopedResearchFactory 运行。这不是将旧万能工具助手升级为已授权企业工具，它仍无正式执行授权。

`EnterpriseBootstrapTest` 3项通过：模式降级/混合profile拒绝、profile禁自动MCP、扫描保留正式模块且无旧RAG/工具。`mvn -o -q -DskipTests package`通过（仅打包，不能当测试）。真实新库迁移、服务启动、模型、浏览器与重启验收由主任务另行记录。
