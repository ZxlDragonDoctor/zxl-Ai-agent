# MCP 发现后断连验收夹具

`transport-cut.mjs` 是测试工具，不属于产品的运行时降级。只代理明确指定的 loopback TCP 端口；`cut()` 关闭该代理拥有的连接，保持后端服务和其他主应用不动。TCP 回显测试验证了切断前可双向传输、已有连接关闭、后续连接不转发。

`run-transport-cut.mjs` 采集固定 `tool-failure-3`。需先独立启动主应用和 MCP 后端，主应用的 `AGENT_SECURITY_MCP_ORIGIN` 必须指向本代理端口。不要复用共享主应用的 endpoint，不要停止共享 MCP。环境变量：

- `LESSON22_MCP_BACKEND_PORT`：独立 MCP 后端端口，例如 18323；该 MCP 可调用现有独立知识 Worker。
- `LESSON22_MCP_PROXY_PORT`：主应用指向的空闲代理端口，例如 18313。
- `LESSON22_ORIGIN`：独立主应用/预览的 loopback origin；主应用 allowed-origins 应与之相同。
- 专用 `lesson22-*` 租户及 `LESSON22_LOGIN/PASSWORD/MAINTAINER/MAINTAINER_PASSWORD`。维护者仅用来刷新发现，运行由员工发起。
- `LESSON22_PSQL` 和显式 PG 客户端连接环境变量。

```sh
node scripts/lesson22/run-transport-cut.mjs docs/learning/examples/lesson22-cases.json qwen-plus fixture-v1 mcp-cut > evidence.json
```

脚本先建立代理，刷新真实发现并核对 READY、1 个工具和 endpoint，再切断代理，运行真实 Research 问题。只有观察到 TOOL_STARTED 及 MCP 网络/协议/超时错误才输出证据；模型在工具前失败不会被视为本故障成功。PG 独立核对运行时间窗内测试租户的写入。

当前证据边界：代理自动测试通过；采集器仅完成语法检查，尚未在独立正式服务上运行，不代表 `tool-failure-3` 已验收。页面截图、真实运行结果和评分需另行完成。

## 其余未覆盖场景

- `malformed-reply` 与 `response-limit`：适配器已有 JSON/块数/131072 字节检查（`StructuredMcpToolCallback`），仍需发现成功后由测试 MCP 返回畸形/超限 CallToolResult。不能将停 Worker 得到的业务失败替代这两种场景。
- `retrieval-timeout`：需要发现与调度成功后在 Worker 实际查询阶段阻塞，并验证工具错误来自该阶段。普通模型超时不能代替。
- budget 的 tool-wait、worker-queue、planner-wait、synthesis-wait：除 planner 外，1 秒总预算往往先被模型消耗，必须记录实际进入目标阶段再阻塞和截止的证据。生产环境没有公开“按场景暂停”的配置；可用独立可控依赖或 Java 生命周期测试入口，不能仅改案例标签。
