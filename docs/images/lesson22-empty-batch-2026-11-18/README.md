# 第 22 课空知识库两题批量验收

专用空知识库租户 `lesson22-empty-dev-v2`，真实 qwen-plus、正式 MCP、PostgreSQL。两题按固定开发集输入顺序运行，均没有工单写入。本批 2 题均未达到严格拒答标准。

| 案例 | 实际结果 | 评分理由 |
| --- | --- | --- |
| abstention-2 未发布的合作协议 | 规划阶段失败，未启动工具 | 不能证明完成了空检索并明确拒答 |
| abstention-3 夜间班车时刻 | 完成知识工具调用，最终返回固定拒答 | 向量阶段失败，`degradations` 含 `VECTOR_CHANNEL_FAILED`；不能把降级后的空结果视为完整检索成功 |

[评分输入](evidence.json) · [报告](report.json) · [中文导入](report-zh.png) · [切换英文](report-en.png)

页面实际显示实测 2/18、通过 0、失败 2，其他 16 题缺证据。没有将失败删除或通过更换案例标签变为成功。与其他日期报告的模型/知识版本及场景不同，不能直接合并成同一条件的模型改善结论。

本批定位到空知识库仍调用查询向量服务：即使数据库中没有活动文档，也会受外部 embedding 故障影响。后续修正须先用真实 PostgreSQL 测试证明空库可以跳过无用请求，非空库仍正常检索，数据库故障和不兼容索引不能伪装为空。此次失败证据保持原样，不能用修正后的测试结果追认它已通过。

## 已落地的工程修正

`RetrievalService.profileReady` 的 PostgreSQL 查询现在返回是否存在本租户活动文档；无活动文档时，vector 阶段明确标为 `SKIPPED_EMPTY_INDEX`，不再请求 embedding。关键词、截止与最终授权检查仍执行。非空库仍检查兼容索引后正常检索；SQL 查询失败直接传播。空库判定是该次 SQL 的观察，不承诺整个请求的跨阶段快照一致性。

新增 `RetrievalEmptyIndexPgTest` 先复现 embedding 调用次数应为 0、实际为 1；修正后两项通过。连同 RetrievalPgTest、RetrievalVectorBudgetPgTest、RetrievalRerankBoundaryPgTest、RetrievalApiTest 和 RetrievalAlgorithmsTest，共 25 项通过，无错误、失败或跳过。测试数据库为 PostgreSQL 16.15，独立端口 15493。

本轮未重启当前 Worker，也未执行修正后新一轮真实模型调用，页面报告仍忠实显示修正前失败。
