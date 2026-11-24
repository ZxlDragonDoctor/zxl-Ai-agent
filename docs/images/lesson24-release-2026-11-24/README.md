# 第24课：真实发布切换与回退验收（2026-11-24）

## 产物与运行边界

操作仅针对5218的lesson24-paired-recovery恢复组。5215原组、5216源组、5217仅PG恢复组保留。PG容器及会话卷均未重建或覆盖。私密环境、二进制JAR/ZIP、登录Cookie均未提交。

A：Java干净提交f0074f3，JAR SHA256见build-a.json，前端沿用8965687的旧干净产物。B：Java干净提交a97806d，前端来自f0074f3的干净构建。B实质改进是重复schema模块在环境读取和数据库访问前拒绝；不是仅修改镜像tag。两版业务schema、会话格式相同。source-a/b.json、release-a/b.json记录固定源码和实际镜像ID。

切换工具为6195a9e提交中的版本。initial→A时尚未增加报告phase字段，后续三次切换均记录phase；这不是重新执行或补造先前记录。

业务页面截图来自5218的实际干净镜像。图谱新知识截图来自5212当前工作区生产构建预览（141节点/192关系），不是声称A/B镜像已包含本批新文档。A/B前端图谱节点数分别137/138，旧产物及新产物区别保留。本批不更改任何个人学习进度或笔记。

## 操作与结果

1. initial→A：候选JAR以低权限运行身份只读验证security/knowledge，然后停应用、重建main、依次重建侧车。HTTP、实际镜像与发布端口通过。
2. A→B：真实Java及前端镜像均切换。重复参数`security security`从A的0/SCHEMA_VERIFIED变为B的2/SCHEMA_MODULE_INVALID，证明新Java契约实际生效。
3. B→A：旧JAR重新通过当前数据库只读校验，回退后CLI恢复A行为；新登录读历史、展开执行和切换英文均正常。
4. 回退后通过正常API新建一条测试对话，得到2条消息、1次completed运行和busy=false；随后只删除该临时对话。归属表保留其conversation/run的2条记录，5→7；没有为追求相同摘要而删审计/归属数据。
5. A→最终B：再次执行检查和切换，确认最终CLI为B、原消息/报告仍可读取，MCP发现刷新READY。以后重启此组必须保留`target/lesson24-final-b.json.compose.json`镜像覆盖，否则基础私密env仍指向旧镜像。

所有切换报告见initial-to-a、a-to-b、b-to-a、final-b.json。`ready`只代表发布检查，本页另列业务验证，不能由状态直接推出模型或审批全覆盖。

## 独立数据验证

before、A、B及rollback-A所选12张稳定业务表行数和逻辑摘要完全一致，包含预算、报告、工单和知识数据，排除不断变化的登录/权限审计。临时写入仅使resource_owner新增2条；after-write与final-B的12表再次完全一致。不是整个数据库字节相等的主张。

四次新登录读取同一历史报告，report SHA256一致、status=completed、cleanupPending=false。原会话2条消息、1次运行的详情SHA256始终为44b0ac…66c。最终容器实测JAR SHA与干净产物一致、四镜像匹配、文件卷名及PG容器ID保持，见final-runtime.json。最终独立PG预算查询确认：运行与周期均spent2629、reserved0、acceptedCalls2，SETTLED actual合计2629，见final-pg-budget.json。

本批未发起新的真实回答模型请求。新写入为demo-literal-containment-v1的证据不足拒答，用于验证文件和归属持久性；历史2629 token为上批真实模型调用账本，不能当成本批新模型结果。

## 停机前拒绝

另从备份恢复出专用agent_incompatible_release克隆库，注入合成V999迁移历史。候选检查返回SCHEMA_CHECK_FAILED，四应用及PG的容器ID、镜像和Running状态前后一致。该库仅用于拒绝验证，未挂到实际服务，也不修改正式恢复库的历史。

origin故意填写另一运行组5216，同样在停机前返回PUBLISHED_ORIGIN_MISMATCH，五容器状态保持一致。见guards.json及guard-*.json。端口回归测试曾真实RED（Missing expected rejection），补齐配置及容器实际映射检查后GREEN。

## 测试与构建

- 干净A Maven定向8项通过；干净B定向9项（SchemaVerifier5、EnterpriseBootstrap4）通过，均无失败/跳过。A/B package均成功。
- `node --test deploy/enterprise/*.test.mjs`52/52通过，其中发布切换9项。
- 图谱回归37/37、前端生产构建通过。已有大chunk提示保留，不等同于容量优化已完成。
- 两个Java镜像和B前端按固定基础镜像digest真实构建；Dockerfile必填ARG没有默认值产生构建提示，但实际传入固定digest，构建成功。

## 逐状态截图

每个阶段在独立Chrome上下文重新登录，不拍摄已填密码表单；点击历史后等待加载结束，再展开记录和切换语言。

| 阶段 | 操作、预期与实际 | 截图 |
| --- | --- | --- |
| A | 登录页→新登录→选择历史，原消息存在 | a-01-login.png、a-02-history.png |
| A | 展开思考/上下文记录→切换英文，记录可读 | a-03-execution.png、a-04-english.png |
| B | 同样四步，消息和执行记录保持 | b-01-login.png至b-04-english.png |
| 回退A | 同样四步，旧Java实际运行且原历史保持 | rollback-a-01-login.png至rollback-a-04-english.png |
| 最终B | 同样四步，最终运行B且历史保持 | final-b-01-login.png至final-b-04-english.png |
| 新图谱概念 | 打开阅读卡→英文→2D，解释发布与数据恢复边界 | graph-01-concept-zh.png至graph-03-concept-en-2d.png |
| 新图谱关系 | 点击发布工具证据→切回3D，源码路径及阅读卡持续 | graph-04-source-en.png、graph-05-source-en-3d.png |
| 图谱筛选 | 搜索发布切换并选证据类型→点击节点，显示工具源码 | graph-06-filter-release-evidence.png |
| 最终课程摘要 | 第24课阅读卡说明已验证A/B回退及仍待TLS/完整业务/CI | graph-07-lesson24-status.png |

## 尚未覆盖

未演练破坏性schema变更后的业务降级、任意旧Java数据语义兼容、活动模型运行中发布、零停机、多主机或Kubernetes滚动更新。会话格式兼容声明不能替代真实数据回归。失败后的部分混合状态需要人工审查和有序恢复，不是自动回退系统。外部TLS、完整双租户审批、容量与CI仍未验收，第24课保持进行中。

补充边界：Docker客户端子进程超时不等于服务端one-shot容器已取消；失败后须检查残留容器/连接。该项未做故障注入验收，不声称所有超时均自动清理。
