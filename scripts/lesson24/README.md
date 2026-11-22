# 第24课首批：环境预检

运行：`node scripts/lesson24/preflight.mjs --role main`（也支持worker、mcp）。通过退出0，配置问题退出1，命令参数错误退出2。测试：`node --test scripts/lesson24/preflight.test.mjs`，本批10项通过。

这是明确的**仅环境变量部署契约**，不读取application*.yml、不推断Spring配置优先级、不连接数据库/模型、不检查端口或依赖版本。现有运行如果依靠配置文件，可以正常启动而此检查仍报缺少环境变量；操作者应将部署所需值显式注入环境，不得据此声称已启动或完成部署。输出只含固定角色、错误码、变量名和范围，绝不包含提交给检查器的值。

源码依据：FormalSecurityConfiguration、FormalKnowledgeGatewayConfiguration、FormalKnowledgeWorker、FormalKnowledgeMcpWorker、ScopedKnowledgeClient、WorkerAuthenticationFilter、HttpKnowledgeEmbedding、HttpReranker。

main检查required模式、PG连接变量、DashScope模型密钥、内部服务token和目标来源；worker/mcp直接读取LESSON17_INTERNAL_SERVICE_TOKEN，main则优先AGENT_SECURITY_INTERNAL_SERVICE_TOKEN。worker按实际demo/http embedding、rule/http rerank检查对应变量，不要求它不用的主应用模型key。demo是源码明确支持的课堂模式，不将其当成真实向量教学主线通过。

内部客户端当前硬性限制http://127.0.0.1:显式端口，因此不能直接把docker compose服务名填进去。需要在后续部署设计中处理同主机/网络命名空间边界或受审实现新的认证传输，不擅自放宽来源。主应用allowed-origins可空（同源仍可工作），若配置则接受源码支持的HTTP/HTTPS精确origin，拒绝通配符、路径和凭据。没有发明“生产一定HTTPS”的现有应用规则；反向代理TLS与外部访问应另行验收。

本批只覆盖上述启动必要子集。可选Worker HttpAnswerModel、预算数值、Skill版本、端口、磁盘持久化、模型网络可用性、TLS、迁移权限、镜像摘要、容器及重启回滚尚未由此脚本验证；预检通过不等于完整配置正确或课程完成。

TDD证据：target/lesson24-preflight-red.log记录缺模块；随后加入空实现，target/lesson24-preflight-behavior-red.log记录缺失配置/来源/CLI行为断言失败；实现后target/lesson24-preflight-green.log最终10项通过。未修改Dockerfile、pom或主应用。本机main、worker、mcp三角色显式环境CLI分别退出0，不代表网络或部署验收。

JDBC检查是本课显式网络部署契约：仅接受jdbc:postgresql://host[:port]/database及可选查询参数；拒绝省略host/数据库、URL内嵌用户名密码和fragment。JDBC驱动支持的其他短格式不在本契约范围，不声称驱动不支持。URL红绿记录另存target/lesson24-preflight-url-red.log。
