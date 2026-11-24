# 企业 Java CI 测试边界

先执行分类门禁，再执行完整 profile：

```sh
node scripts/lesson24/java-test-inventory.mjs --check
mvn -B -ntp -Penterprise-ci verify
```

Node 门禁由 workflow 的强制独立步骤执行，未在 Maven 中新增 Node/exec 插件；只运行第二条不能冒充已执行分类检查。Maven profile 保持 Surefire 默认发现规则，仅排除 `java-test-policy.json` 中10个精确旧演示类，不使用类名白名单、跳过测试、忽略失败或并行缩减。普通 Maven 默认行为不受该 profile 改动。

## 当前盘点

2026-11-24 源码共有174个 Surefire 命名的测试文件，164个默认 CI，10个明确人工演示。自动描述分类为46个直接PG标记、115个隔离测试/受控HTTP/进程夹具、3个课堂第九课H2对照。分类依据文件名、环境/数据库引用或已知课堂fixture，不是逐个测试方法的运行证明；实际用例数量、失败和跳过须读本次Surefire XML，不能说“174项测试通过”。完整每文件盘点生成在 `target/enterprise-ci-java-inventory.json`。

默认 CI 包括全部现有现代测试类：身份/租户/权限、知识接入/检索/引用/记忆、MCP/Skill、多Agent、审批写入、执行/事件、预算/路由、数据库版本验证等。真实PG不可用时测试失败，不用H2替代；第九课H2只是单独机制对照，不能描述为正式PG验收。

10个人工例外均为旧 `@SpringBootTest` 演示：根应用启动、LoveApp、Manus、旧RAG文档加载、文件/PDF/资源下载/终端/网页抓取/搜索工具。每个精确路径及外部依赖理由在policy里；它们会加载课堂根应用/local配置、真实模型或外部网络，不能在无凭据CI里默认为企业隔离测试。排除不是功能已验收的证明，也不声称现有契约测试覆盖了它们的所有真实副作用。真实供应商/浏览器教学证据仍需单独保存。

## 防漏规则

新 `Test*.java`、`*Test.java`、`*Tests.java`、`*TestCase.java` 默认纳入CI，无须更新执行白名单。带JUnit方法但不被Surefire发现的文件直接失败。新完整SpringBootTest须明确登记为经审查的CI上下文或人工演示，防止无意加载真实课堂服务。人工条目必须有现存精确路径和非空理由；pom排除必须与policy严格相等。门禁拒绝 profile includes 白名单、skip/忽略失败配置以及现代条件跳过注解、Assumptions。

这是源码约定检查而不是Java AST或网络沙箱，不能证明任意新增代码绝不访问外部服务。CI不注入真实模型/搜索密钥；新增外部集成必须显式使用本地受控服务或提出独立验收边界。Windows-only终端测试在Linux应验证明确不支持的返回契约，而不是Assumptions跳过或修改生产工具为未经授权的Linux执行器。

## 真实依赖与资源

提供独立PostgreSQL+pgvector实例和显式 `LESSON12_DB_URL`、`LESSON12_DB_USER`、`LESSON12_DB_PASSWORD`，并设置 `AGENT_DB_SCHEMA_MODE=migrate`。CI数据库账号需要在此隔离实例中创建/删除临时database、role及终止自己测试的连接；不可连接生产数据库。这个高权限测试fixture不等于生产运行身份策略。

`LESSON17_INTERNAL_SERVICE_TOKEN`仅用synthetic值，但仍须满足源码token规则（至少43字符及足够字符种类），不能因为是测试就传不合法短串。不需要真实DashScope/搜索/embedding/reranker密钥：现代供应商传输测试使用本地HTTP夹具，Embedding fixture是确定性向量，均不作为真实模型质量证据。

测试会绑定随机loopback端口、产生短生命周期Java子进程、读写独立临时文件并使用Mockito静态mock。执行节点须允许这些操作，保留子进程日志；内存参数/超时和JDK、Node版本由CI显式固定。新环境中的时序、网络或进程失败必须诊断，不将类追加到manual清单来伪造绿色。不得把service不可用或环境错误记为跳过通过。

本子任务仅验证6项分类器Node测试与当前源码盘点；未在脏工作区运行完整Maven套件，完整clean CI结果由父任务另行记录。
