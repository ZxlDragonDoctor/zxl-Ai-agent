# 第二十二课：Agent 评估与持续改进

选一次提示词或检索改动，用同一批工单和企业知识问答看它带来了哪些收益和退化。每个案例记录问题、预期引用、允许的工具、拒答条件和运行预算。

1. 将正常问答、空召回、越权查询、工具故障、审批和执行超时纳入固定数据集。先把开发集与最终保留集分开。
2. 用规则断言租户权限、引用来源、工具调用和副作用。评分报告保留逐题结果和失败原因。需要判断措辞或业务质量时，再让人工复核，并用人工答案对照校准模型评分。
3. 固定案例集版本、模型和知识库版本。比较改动前后结果时，同时记录引用通过率、失败分类、耗时和实际可获取的 token 数据。
4. 从一个失败用例回到运行 ID 和日志，找出可复现的问题。修复后重跑开发集，最后用保留集复核。

面试练习：一次改动提高了平均得分，但让跨租户引用断言失败。你会怎样确定它能否上线？

## 首批实现

`examples/lesson22-cases.json` 固定了 30 个案例。开发集 18 条，保留集 12 条，覆盖引用、拒答、权限、工具失败、审批和预算。文档 ID 与身份是待配置的合成测试数据。

评分器读取真实运行证据或明确标记的测试夹具。为证据记录案例文件的 SHA-256、模型、知识库版本和变体名称。每条运行记录包含 `id`、`status`、`sources`、`tools`、`writes`、`elapsedMs`、`tokens`，以及可选的 `runId`。取不到 token 时填写 `null`。引用从结果证据提取，写入次数需查业务写入记录。

```sh
node scripts/lesson22/score.mjs docs/learning/examples/lesson22-cases.json dev baseline.json candidate.json
```

CLI 按案例列出遗漏、额外引用、额外工具及未授权写入。比较时要求案例摘要、分组、模型和知识库版本相同。候选未通过时退出码为 1，可在 CI 中阻止发布。当前测试覆盖评分规则。固定测试数据部署、真实运行采集和工作台报告仍待接入。

[第 22 课图谱阅读卡验收截图](../images/lesson22-2026-11-15/01-learning-graph.png)

正式工作台的独立冒烟题已通过真实模型、MCP 和 PostgreSQL 验证。提取器要求同一任务的工具结果支持正文引用，写入次数由数据库回执提供。 [截图与报告](../images/lesson22-live-2026-11-15/README.md)

使用 `node scripts/lesson22/collect.mjs dataset.json dev case-a.json case-b.json > baseline.json` 汇总已采集的单题证据。汇总要求文件属于同一数据集、分组、模型、知识库版本和变体。重复的案例或运行 ID 会导致错误。缺失案例由评分器标记为失败。

把评分器生成的对照 JSON 导入知识图谱第 22 课的「评估报告」。真实冒烟结果随页面提供。对照验收使用人工构造的缺失引用证据来检查 UI 的退化提示。全套 30 题的批量执行及真实候选版本比较仍待完成。 [报告页验收](../images/lesson22-panel-2026-11-15/README.md)

## 正式运行采集

`scripts/lesson22/run-formal.mjs` 用专用测试租户登录，通过正式编排接口顺序运行显式选定的案例，等待终态和本地清理，然后通过 PostgreSQL 独立查询测试租户内新增的已批准工单。配置本机工作台的 `LESSON22_ORIGIN`、测试用户的 `LESSON22_TENANT`、`LESSON22_LOGIN`、`LESSON22_PASSWORD`，以及 `LESSON22_PSQL` 和 PostgreSQL 客户端环境变量。验收期间保持测试租户独占。采集器暂不从模型日志导入用量。

```sh
node scripts/lesson22/run-formal.mjs docs/learning/examples/lesson22-cases.json dev qwen-plus fixture-v1 current budget-1 > budget.json
```

该入口支持知识引用和模型等待超时。权限、审批、工具故障和其他预算阶段仍需要独立场景。首轮重复运行保持同一实现，观察到模型在工具调用前失败会同时影响终态、引用与工具三项断言。

空召回题使用专用空知识库租户，执行器统一要求规划一个 Research 任务。评分器只有在研究任务返回明确拒答、同任务的知识工具成功且无命中或降级时，才标为拒答。第 22 课阅读卡也支持导入单份评分报告，可以看到通过项和缺证据项。 [真实空召回验收](../images/lesson22-abstention-2026-11-15/README.md)

使用 `LESSON22_PROMPT_MODE=original` 保留原始问题，或使用默认的 `research-directed` 在知识检索题前加上一条明确的 Research 任务指令。两次采集固定案例摘要、同一个空知识库租户和模型，报告保留提示词模式。实际对照中，基线模型未返回首帧，候选调用了知识工具但未达到拒答判定。两次运行都未通过该题，单次配对不足以估计提示词改动的效果。 [真实提示词配对结果](../images/lesson22-prompt-2026-11-15/README.md)

对照报告的 `coverage` 同时记录基线、候选和两边都有证据的题数。只有两边都测过的题才参与比较退化和改善。候选证据不完整时，发布检查仍然失败。比如 18 道开发题只测了同一道，成对覆盖就是 1/18。

保留集中的两道空召回题已在独立空库租户实跑。报告面板区分实测通过、实测失败、缺少证据。 [真实保留集和配对覆盖验收](../images/lesson22-coverage-2026-11-15/README.md)

五道知识引用题使用 `examples/lesson22-grounding-docs.json` 中的合成资料。每道题分配一个独立测试租户，其中只发布该题对应的文档，避免向量召回受其他题文档影响。先为租户建员工与知识维护者账号，再提供 `LESSON22_MAINTAINER`、`LESSON22_MAINTAINER_PASSWORD` 和正式采集使用的环境变量。

```sh
node scripts/lesson22/publish-grounding.mjs docs/learning/examples/lesson22-grounding-docs.json expense-guide
node scripts/lesson22/run-formal.mjs docs/learning/examples/lesson22-cases.json dev qwen-plus lesson22-grounding-v1 research-directed grounding-1 > grounding-1.json
```

真实开发集验收覆盖两道引用题。会议室显示设备题引用了指定的 `meeting-display`，费用报销题在工具调用前失败。两次工单写入均为零。 [报告与截图](../images/lesson22-grounding-2026-11-15/README.md)

引用组累计实测 5 题，4 题通过。账户恢复、设备归还和无障碍协助三题均使用单文档租户通过，费用报销题保留为实测失败。账号可用 `scripts/lesson22/Lesson22GroundingSeed.java` 离线建立，运行时需要应用类路径及数据库环境变量，密码从采集脚本使用的环境变量读取。 [开发集与保留集报告](../images/lesson22-grounding-2026-11-16/README.md)

Worker 未启动时，正式 MCP 仍接收真实模型选定的知识调用。评分除了检查失败终态和工具已启动，还要求从失败工具事件中提取 `MCP_REMOTE_BUSINESS_FAILURE`，以区分调用前的模型失败。采集时设置 `LESSON22_SCENARIO=worker-unavailable` 并使用独立测试租户与专用服务。 [故障报告与截图](../images/lesson22-worker-fault-2026-11-16/README.md)

审批评分从提交草稿阶段开始。使用 `node scripts/lesson22/project-approval.mjs dataset.json approval-1 observation.json model fixture-v1 current` 将观察结果转成评分证据。观察文件包含提案 API 响应、运行 ID、提交耗时和 PostgreSQL 实测行。数据库行须包含 proposalId、outcome、version、reviewerAbsent 和实际回执数 writes。草稿阶段的模型与检索证据单独记录。 [本批验收进度](../images/lesson22-approval-2026-11-16/README.md)

键盘故障题已取得真实草稿与待审批提案。工单 Skill 的输入规则现支持设备名加具体故障描述，软件请求仍沿用产品与错误码规则。审批阶段的评分核对了 API 和 PostgreSQL，工单回执为零。 [完整截图与评分](../images/lesson22-hardware-2026-11-18/README.md)

权限题 `authorization-3` 只评估审批边界，不重复计算草稿生成阶段的模型与工具调用。`projectEmployeeReview` 要求真实认证员工与申请人不同且同租户，核对审批 POST 路径和完整参数绑定、403 `ACTION_DENIED`，以及前后 PostgreSQL 快照一致、仍待审批且零回执。GET 查看拒绝、错误请求体或跨租户拒绝不能代替该题。运行 `node --test scripts/lesson22/*.test.mjs` 验证反例。

已用同租户另一名员工在正式接口实测审批拒绝，前后 PostgreSQL 状态一致且零回执；中英文阅读卡已导入报告并截图核对。该旧提案已过期，权限门禁在过期判断之前执行，本次不作为有效期内审批证据。[请求、快照与页面验收](../images/lesson22-authorization-2026-11-18/README.md)

`tool-failure-3` 提供了[发现后断连夹具](../../scripts/lesson22/transport-cut.md)：先发现真实 MCP 工具，再只切断专用代理自己的连接。采集器要求工具确实已启动且错误来自 MCP 传输，模型在调用前失败不能替代工具故障。TCP 夹具已通过自动测试；独立服务启动被自动审批拒绝，真实模型故障采集与页面验收尚未执行。

评估也应推动工程修正：真实空库题发现无活动文档仍请求 embedding，导致外部故障影响拒答。现由 PostgreSQL 的活动文档检查控制跳过空库向量阶段，记录 `SKIPPED_EMPTY_INDEX`，保留权限、截止与最终发布检查。先复现再修复，25 项检索相关测试通过；运行中的 Worker 未更新，未将测试结果追认为真实模型题通过。[失败证据及修正边界](../images/lesson22-empty-batch-2026-11-18/README.md)

页面验收还需核对提案属于本次运行。一次批量脚本曾捕获旧消息中的审批回执，截图复核后撤销该观测；不能仅凭页面存在“待审批”就认定当前案例成功。采集应显式新建会话并核对流事件与提交 runId；评分器和汇总器均拒绝同一 execution 被多个案例复用。

授权组现已覆盖五题：跨租户文档、他人私有记录、普通员工审批、撤销会话、禁用会员后的只读归档。[权限评估导读与复跑](22-access-evaluation.md)。设备草稿批量验收中，门禁卡题通过，投影仪、办公椅和打印机没有取得有效的可提交草稿；这些前置失败保留，不算审批阶段通过。[设备输入、失败截图与待审批证据](../images/lesson22-device-batch-2026-11-18/README.md)

本批评分相关回归 21 项、检索相关回归 25 项、图谱回归 37 项通过，前端生产构建通过（仍有现有大包体积提示）。页面实际检查了开发集/保留集报告切换、中文/英文切换、课程筛选、阅读卡及关联关系。当前仍缺部分工具故障、分阶段预算、完整重复对照及修正后的真实 Worker 链路，课程保持进行中。

后续已补 6 个可控机制场景：发现后断连、超限响应、实际 lookup 截止，以及 Worker 排队、规划等待和汇总等待的截止。24 项 Java 测试、23 项评分回归通过；不等同于真实模型质量或性能测试。评分现在要求匹配的工具错误和故障场景；分阶段预算必须证明进入指定阶段且清理完成。[机制、证据与未覆盖项](../images/lesson22-controlled-2026-11-19/README.md)

第 22 课阅读卡可直接选择保存的权限、待审批、空库失败和可控机制报告，无须下载后重新导入。模型标识和知识版本独立展示，仍保留自定义导入；切换报告不会修改个人掌握状态。
