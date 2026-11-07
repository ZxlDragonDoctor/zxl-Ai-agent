# 第十七课补充：失败必须能够定位，也不能泄露资料

同一个EXECUTION_ERROR可能来自模型HTTP、无效引用、原文已撤销或整个研究任务超时。页面安全提示可以保持通用，服务端必须保留固定阶段和受控分类，才能判断该修哪里。

`ResearchRunner`现在为一次执行维护固定阶段：发现工具、模型选工具、参数校验、工具调用与响应、原文核验、回答前再核验、模型回答、引用校验、最终原文复核和发布。异常向上抛出之前，只记录阶段枚举、白名单错误码和最多16层异常类型。不记录异常消息、堆栈、目标、原文、回答、账号、运行标识或凭据；未知消息映射为RESEARCH_EXECUTION_FAILED。

例如答案缺少`[C1]`，诊断为`stage=ANSWER_VALIDATION, code=RESEARCH_MISSING_CITATION`；这时应检查引用约束，不能提高工具超时。权限、取消和截止仍按原异常传播；本批没有增加重试、放宽引用守卫或延长30秒研究预算。

`ResearchDiagnosticsTest`三项先红后绿，覆盖固定分类、包含secret的嵌套异常不泄露，以及取消/权限保持原对象与语义。初次测试fixture漏了工具响应code/message，停在错误阶段；修正后关闭日志重新复现三项失败，再恢复实现。12类72项相关回归通过（其中2项真实PG），不与前述120项简单相加。日志为本地忽略目录`target/lesson17-research-diagnostic-red-corrected-20261107.log`、`target/lesson17-research-diagnostic-green-20261107.log`。

这些日志不能反向确定早先没有诊断的失败原因。实际模型页面另记结果；门内modelCalls计数也不等于已发送HTTP或计费次数。后续链路追踪与用量账本需独立实现。
