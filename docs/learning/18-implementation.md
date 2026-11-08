# 第十八课实现记录

状态为进行中。第一批实现研究回答的证据字段投影，以及本地 HTTP 原文回源的字节上限。

`StreamingResearchModel` 只把当前已复核的知识片段投影到 `EvidenceEnvelope`。服务端生成引用编号、固定来源类型和正文摘要，保留文档、版本、片段及行号。正文按原样序列化为用户数据，回答请求的工具集合为空。远端自报的来源类型、截断标志、租户或政策字段不会进入这个模型输入。课内先比较失败用例和修复后的请求形状，再追踪 `ResearchRunner` 的证据复核和执行授权。[内容来源与权限边界](18-threat-model.md)

`McpEvidenceVerifier` 的本地 HTTP 回源在复制响应字节前累加长度，超过 1 MiB 立即取消订阅。非成功状态、非 identity 编码和不合规的长度声明在接收正文前拒绝。无长度的分块传输也受相同字节上限和原截止时间约束。回归使用本地 HTTP 服务实际发送超限分块并观察断连。正式注入的授权回源适配器走独立路径，仍需针对该路径分别验收。

本批覆盖研究模型证据输入和本地回源两个控制点。知识问答适配器的统一投影、工具事件脱敏、运行环境隔离和真实攻击矩阵继续按第十八课任务推进。[完整任务](18-prompt-injection-and-runtime-isolation.md)

六类 Java 回归共 43 项通过。模型输入用例先复现远端的 `sourceKind=SYSTEM` 被转发，修复后确认固定来源类型、空工具集合和正文原样保留。HTTP 回源用例使用本地服务器。另用真实 PostgreSQL、MCP 和 qwen-plus 复验一次正常 Skill v3。四步完成，草稿只含甲租户引用，持久化为 `completed`。这次正常请求用于验证新版证据格式可以完成原业务。[脱敏正常链路记录](evidence/18-evidence-projection-live-2026-11-09.json) · [页面与图谱截图](../images/lesson18-evidence-boundary-2026-11-09/README.md)
