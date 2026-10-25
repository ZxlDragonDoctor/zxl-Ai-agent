# 第十五课 MCP 运行时

这里实现的是 **MCP Java SDK 0.7.0 的旧版 SSE**，使用同版本官方 `mcp-spring-webmvc` 的 `WebMvcSseServerTransport`，不是 Streamable HTTP。`pom.xml` 显式固定已由 Spring AI M6 解析的版本，未升级 Spring AI/Boot。MVC 配置仅在独立 `lesson15-mcp` profile 生效，不改变主应用 MVC。

## 职责

| 类 | 职责 |
| --- | --- |
| `KnowledgeLookupTool` | `knowledge_lookup_v1` 参数/业务结果；有界查询工作池；复用 `RetrievalService` 的 PG 当前版本检索 |
| `KnowledgeMcpServer` | 独立 loopback SSE 进程，`/sse` 和 `/mcp/message`；请求体上限及浏览器 Origin 拒绝 |
| `KnowledgeMcpClient` | 固定目标、发现和 schema 校验、稳定别名、生命周期、原始截止时间、取消和有限只读重试 |

服务端身份固定为 `demo-enterprise`，不是登录实现。客户端只接收部署时的 `http://127.0.0.1:端口`，不接受模型指定地址。发现只接受固定服务名 `zxl-knowledge` / 版本 `1.0.0` 和唯一、完全一致的工具 schema；发现不能扩张 allowlist。

给模型的名称为 `knowledge__knowledge_lookup_v1`，保留 `serverId=knowledge`、原名、`v1` 和 schema SHA-256。schema/服务变化须显式适配，不能自动接受。

## 参数与结果

参数：`query` 必填，非空且最多 2000 UTF-16 单元；`topK` 为 1..5 整数，默认 3；`mode` 为 keyword/vector/hybrid，默认 hybrid；`budgetMs` 为 100..10000 整数。客户端总是用实际剩余预算覆盖 `budgetMs`，包括排队、发现、退避和网络等待。未知字段、伪造 tenant/user、任意 URL/SQL/路径均不接受。

输出：一个 MCP 文本块，内容为现有 `ToolResult(success,code,message,data)`。成功 data 直接使用 `RetrievalService.Response`，保留 trace、实际阶段/降级和 `hits`；每个 hit 保留 document/revision/chunk/generation、文本与真实 UTF-16/行范围。成功空 hits 与存储不可用的失败分开。

remote hit 仍是不可信输入。调用方必须在模型发送及结果发布前，复核可信范围下当前文档版本、generation、chunk 和精确原文范围；不得把发现结果、远端引用 ID 或 URL 当权限证明。已知指令注入隔离复用第十四课规则，不能称为完整攻击防护。

## 生命周期与资源边界

`UNAVAILABLE → CONNECTING → READY`；失败回到 `UNAVAILABLE`；`close()` 后永远 `CLOSED`。刷新显式重建连接；关闭重复调用安全。服务器和客户端各 2 个工作线程、8 个等待槽；实际线程结束才释放执行容量。客户端 SDK 交换串行化，避免旧版 servlet transport 的共享会话广播问题；这是一个可信本机应用客户端的教学部署，不作为多租户 MCP 网关。

只读网络 I/O 故障最多重试 1 次，原始剩余预算必须大于 250ms，退避 50ms；参数/权限/业务/协议/超时/取消不自动重试。`retryAllowed(false,...)` 永不重试写工具：超时只能说明结果未知，后续写操作需要 operationId、唯一约束、请求指纹、结果查询和审批绑定。

取消停止本地等待与后续调用，并中断 SDK 等待；SDK 0.7.0 当前封装没有按请求确认远端取消的公共能力。服务端仍可能继续执行直到自己的预算或查询中断生效，不能声称取消等于远端停止/事务回滚。迟到结果会再次检查原始截止和取消信号，不能发布为成功。

参数限制在本项目序列化后检查；服务器 JSON-RPC POST 在 SDK 解析前最多读取 17409 字节，超 17408 返回 413。结果业务 JSON 上限 131072 字节；客户端适配器限制同样在 SDK 完整分配响应后检查，**不是流式内存保护**。原始结果、模型预算视图和页面预览由现有 `StructuredMcpToolCallback` 分离。

错误码：`MCP_INVALID_ARGUMENT`、`MCP_NOT_AUTHORIZED`、`MCP_DEADLINE_EXCEEDED`、`MCP_CANCELLED`、`MCP_CAPACITY`、`MCP_REMOTE_BUSINESS_FAILURE`、`MCP_NETWORK_FAILURE`、`MCP_PROTOCOL_FAILURE`、`MCP_SCHEMA_CHANGED`、`MCP_INVALID_RESPONSE`、`MCP_CLOSED`。公开错误不包含底层异常、凭据或数据库连接内容。MCP `isError=true` 优先级高于文本中的 success；适配器的 `MCP_TOOL_ERROR.data.remoteCode` 只接受固定安全枚举，不构成自动重试授权。

## 运行

先按第十二课配置真实 PG 与当前兼容的 embedding 环境（`LESSON12_DB_*`、`LESSON12_EMBEDDING_*`）；不要将本地凭据提交。执行 main class：`com.zxl.zxlaiagent.knowledge.mcp.KnowledgeMcpServer`。默认 `127.0.0.1:18135`，可通过部署环境 `LESSON15_MCP_PORT` 调整。工具不调用重排或生成模型，检索使用第十三课当前生效索引；embedding profile 必须与导入资料一致。

启动顺序：真实 PG → MCP 服务 → 主应用；核心应用不依赖 MCP 启动就绪，研究能力通过明确的状态/发现操作恢复。关闭拥有该进程的终端或发送正常进程退出，shutdown hook 关闭服务器、连接和工作池。

测试：`KnowledgeMcpContractTest` 是离线边界，`KnowledgeMcpTransportTest` 是真实 loopback SSE 加合成 handler，`KnowledgeMcpPgTest` 才是实际 SSE + PG（显式 demo embedding）。它们均不代表真实模型效果或前端截图已验收；完整记录见课程实现文档。
