# 第 22 课提示词配对验收

同一空知识库租户、真实 qwen-plus、正式 MCP 和 PostgreSQL，依次运行开发集的同一个空召回案例。唯一主动改变的参数是提示词模式。运行时仍可能受模型服务波动影响。

| 模式 | 实际结果 |
| --- | --- |
| 原始提示词 | 模型未返回首帧。运行失败，无工具调用 |
| 明确 Research 任务 | 调用了预期工具，工作流完成，未满足拒答断言 |

该题两次都没有通过，另 17 个开发案例缺少本次运行证据。两次工单写入均为零。

[基线证据](baseline-evidence.json) · [候选证据](candidate-evidence.json) · [对照报告](comparison.json)

[打开阅读卡](01-bundled.png) · [导入对照](02-comparison.png) · [错误文件提示](03-invalid.png) · [英文结果](04-english.png)
