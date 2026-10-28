# 第十七课正式 MCP v2

`KnowledgeMcpServer` / `KnowledgeLookupTool` / `KnowledgeMcpClient` 是独立 legacy v1 课堂实验。正式装配必须使用 `FormalKnowledgeMcpServer`、`FormalKnowledgeMcpTool`、`FormalKnowledgeMcpClient`，服务端只注册 `knowledge_lookup_v2`；模型名称固定 `knowledge__knowledge_lookup_v2`。两个服务不得在同一监听端口并存。

## 两层身份

1. `LESSON17_INTERNAL_SERVICE_TOKEN` 是 32–256 位 base64url 字符的共享服务凭据。正式 server 缺少或格式不合格时无法启动；GET `/sse` 与 POST `/mcp/message` 都要求 `Authorization: Bearer …`。Origin/loopback 是附加边界，不能替代凭据。不要将凭据交给浏览器、模型、工具事件或日志。
2. 每个工具调用签发单次 PG ticket。`audience=knowledge-mcp`、`action=knowledge.read`，actor 来自票据的权威会话，不来自查询参数、最近租户字段或共享连接。

SDK 版本保持本地已核实的 **0.7.0 legacy SSE**。该版本 transport 只有 `HttpClient.Builder` 构造器，没有 request-header builder API；`InternalServiceHttpClient` 委托实际 HttpClient，向同一固定 origin 的 GET/POST 注入 Authorization，并拒绝重定向。共享 `SingleClientSseTransport` 的连接代际与响应路由保持原实现。

## 两份 schema

`FormalKnowledgeMcpProtocol.MODEL_SCHEMA` 只允许 `query/topK/mode`；`WIRE_SCHEMA` 另要求 Java 生成的 `budgetMs/authorization`。发现服务时审核 wire schema，交给模型时改用审核过的 model schema；`DiscoveredTool` 分别保存 `schemaSha256`（model）和 `wireSchemaSha256`（wire），版本为 v2。

双方调用同一 `requestHash()`：严格校验四项业务参数，按 TreeMap 生成 UTF-8 JSON，再计算 `ServiceCallAuthorizer.requestHash("MCP", "knowledge_lookup_v2", bytes)`。摘要包含最终 budgetMs，不包含 authorization。模型传入 budget、身份或 authorization 一律拒绝。

## 显式接线

```java
new FormalKnowledgeMcpTool(tickets, authorization,
    (actor, request) -> scopedFactory.retrieve(actor, request));
new FormalKnowledgeMcpClient(endpoint, internalToken, tickets, authorization);
client.call(actor, FormalKnowledgeMcpProtocol.EXPOSED_NAME,
    modelArguments, budget, cancelled);
```

`Lookup.retrieve(ActorRef, RetrievalService.Request)` 返回现有 hits envelope 的对象。注入方必须在实际 SQL/embedding/来源读取/索引发布中继续执行当前授权，不能注入固定 demo tenant 的 RetrievalService 并宣称已实现租户隔离。Tool 消费票据后，排队出队再次 Decision；实际检索不在长事务锁内等待。敏感结果完整缓冲，短 `RESULT_PUBLISH` gate 重新检查后序列化；client 返回前也有发布 gate。

排队、交换锁、发现、调用与回传共享一个绝对 deadline。最终 serverBudget 保留100–500ms回传余量；票据在出队和拿到锁、确定最终 budget 后才签发。工具 RPC 没有自动重试；丢失响应也不能重放可能已消费的票据。取消停止本地等待，不保证召回已发出的网络字节。两个工作线程、8项队列、严格参数、128KiB结果限制及固定安全错误消息保持有界。

发布 gate 保护缓冲结果的授权检查和序列化，SDK 随后发送 SSE 的时间窗不在 PG 锁内；已经启动的外部动作/已发字节不能撤回。调用端发布前再检查，不能把这个边界描述为传输字节与撤销事务完全原子。

## 证据边界

`FormalKnowledgeMcpContractTest` 验 schema；`FormalKnowledgeMcpTransportTest` 使用模拟权威 + 实际 HTTP/SSE，检查GET/POST缺/错服务凭据、共享连接交替actor、重放拒绝与撤销发布；`FormalKnowledgeMcpPgTest` 使用真实 PG 票据/Decision + 实际 SDK SSE，检查篡改、受众、重复、撤销与并发actor。PG测试的 lookup 是合成函数，不能替代真实知识SQL/embedding隔离验收。实际执行结果由本轮主任务集中记录，文件存在不代表测试已执行。

### 2026-10-27 实际执行

先以反射契约测试运行，正式协议尚不存在时实际失败（`target/lesson17-mcp-red.log`，ClassNotFoundException）。实现后独立 javac probe 的4项契约、4项实际HTTP/SSE模拟权威测试通过。Maven阶段使用本地真实PG环境，执行：

```powershell
mvn -q '-Dtest=FormalKnowledgeMcpContractTest,FormalKnowledgeMcpTransportTest,FormalKnowledgeMcpPgTest,KnowledgeMcpContractTest,KnowledgeMcpTransportTest,KnowledgeMcpSessionTest,KnowledgeMcpDiscoverySnapshotTest,KnowledgeMcpFailureClassificationTest' test
```

实际退出0，**8类31项，0失败、0错误、0跳过**：正式契约4、正式HTTP/SSE4、正式PG3、legacy契约6、传输7、会话3、发现快照1、失败分类3。依据为本轮Surefire XML；汇总 `target/lesson17-mcp-results.json`，日志 `target/lesson17-mcp-maven-green.log`。target记录不提交为源码。

PG测试实际使用权威票据与撤销事务，调用真正SDK与HTTP/SSE；检索函数是合成函数，尚未在本MCP测试中使用真实业务知识SQL、Embedding或真实模型。主应用/统一聊天接线与页面截图由本课其他单元集中验收。这组结果不能外推为整课完成或全仓回归通过。
