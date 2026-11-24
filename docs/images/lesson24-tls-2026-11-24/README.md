# 第24课：本地CA HTTPS与流式链路验收（2026-11-24）

## 环境与产物

新增独立lesson24-tls组，仅在127.0.0.1:5443提供HTTPS。PG为新容器，agent_tls_recovered从可信成对备份恢复，UUID会话卷为新卷；先准备角色/ACL并撤销10条恢复的活跃会话，再开放应用。既有5215/5216/5217/5218组保留。

应用沿用上批干净B JAR（a97806d，Java镜像18b451…fa9e8）及f0074f3干净前端，未用脏工作区构建新业务镜像。实际nginx1.30.7，OpenSSL3.5.5；镜像基础digest和配置见课程。三个Java角色均实测agent_runtime/validate并连接正确新库，证书与配置是只读bind，见runtime-observation.json。

新图谱内容由5212当前工作区生产构建预览（145节点/197关系），与5443干净业务镜像的截图分开。个人学习进度和笔记未写为已掌握。

## 严格TLS与身份边界

Node HTTPS客户端显式读取本地CA，rejectUnauthorized=true、默认主机名校验，未设置全局忽略验证。TLS1.2和1.3均authorized，TLS1.1被服务端protocol-version告警拒绝。未知CA返回UNABLE_TO_VERIFY_LEAF_SIGNATURE，错误主机名返回ERR_TLS_CERT_ALTNAME_INVALID。明文请求TLS端口返回400且无Cookie。服务端实际叶子fingerprint与生成材料相同。

证书材料仅在忽略提交的target，CA7天、叶子3天，具体有效期和公开摘要见certificate-observation.json；未提交PEM/CSR/私钥或任何系统信任配置。文档中的PowerShell生成命令按原样抽取执行，生成新目录并通过真实有效期、SAN、SPKI和直接issuer检查，见certificate-recipe-observation.json。

登录会话和CSRF Cookie均Secure/HttpOnly/SameSite=Lax。错误Origin/CSRF返回403，未知Host返回421，客户端伪造转发协议/端口/主机未改变Secure属性。另用默认443、80和错误http Origin配合伪造转发头，均403，见scheme-port-origin-observation.json。HTTP Host负例须固定合法TLS servername，否则客户端会先因错误SNI拒绝，不能冒充HTTP421证据；测试已分别校验两层边界。

## 流式与持久化

真实HTTPS SSE返回20个分段，首段138ms、末段1062ms，包含RUN_STARTED和RUN_COMPLETED。保存后2条消息、busy=false。该运行采用demo-literal-containment-v1证据不足拒答，不是新的真实回答模型测试，也不是容量基线。

HTTPS有序重启执行main内部HTTP检查及外部显式CA HTTPS检查。重新登录后原消息详情SHA与原始记录一致，历史completed报告SHA一致、cleanupPending=false，MCP刷新READY。独立PG查询确认原运行/周期均spent2629、reserved0、acceptedCalls2，SETTLED实际合计2629。该账本来自此前真实模型调用，不计作本批新增模型证据。

原恢复样本保留。API和浏览器新建的临时对话均经正常删除入口清理，最终列表只保留原样本；归属和审计按既有业务策略保留，不手工删审计行。

## 缺证书反例与修复

raw-missing-certificate-counterexample.json保留真实反例：Docker Desktop compose run exit1，但create_host_path:false仍生成cert.pem/key.pem两个空目录；五个业务容器ID和Running状态未变。不能把该配置字段的存在当作安全验收。

新增tls-start在任何Docker调用前检查regular文件、可读性、证书有效期/SAN、私钥SPKI匹配及可选直接CA签名。全新空目录实测返回TLS_FILE_REQUIRED且保持为空，五容器状态未变，见guarded-missing-certificate.json。真实材料通过；另用内存中新RSA私钥、叶子充当CA和非法PEM，分别得到KEY_MISMATCH、ISSUER_NOT_VERIFIED和CERTIFICATE_INVALID。它不替代完整PKIX/EKU/path-length检查，真实握手独立验证。

合法启动只返回started/readinessVerified=false；后续独立严格HTTPS和新登录读回通过。外层超时不保证Docker服务端取消；部分启动失败仍需检查现场。

## 浏览器与截图边界

浏览器在独立Chrome上下文对本次叶子SPKI设置精确例外，ignoreHTTPSErrors=false。没有安装Windows/用户浏览器信任；截图只证明UI操作，严格CA/主机名证明来自上述独立客户端。页面异常收集为空。

第一次刷新断言匹配多个同文节点，已限定消息区域、仅清理该次浏览器临时会话后重跑。截图检查发现退出页仍在Connecting，补充等待就绪并重跑相关操作；以下保留最终截图。

| 操作 | 预期及实际结果 | 截图 |
| --- | --- | --- |
| 打开HTTPS登录页 | 表单就绪，未填写密码 | 01-https-login.png |
| 登录员工 | 历史列表可见，无证书/接口错误 | 02-https-history.png |
| 选择HTTPS演练历史 | 原问题和拒答可读 | 03-https-stream-history.png |
| 展开执行/上下文 | 恢复记录可读 | 04-https-execution.png |
| 英文切换 | 控件翻译，原消息保留 | 05-https-english.png |
| 选择企业知识助手 | 新会话Agent类型正确 | 06-https-agent-selected.png |
| 新建 | 空对话创建成功 | 07-https-new-conversation.png |
| 发送 | 进入提交后状态；不宣称固定捕获每一动画帧 | 08-https-sent.png |
| 完成 | 回复及证据不足卡展示 | 09-https-completed.png |
| 刷新历史 | 消息仍在 | 10-https-refreshed.png |
| 请求删除 | 出现确认框 | 11-https-delete-confirm.png |
| 取消删除 | 会话保留、确认框关闭 | 12-https-delete-cancelled.png |
| 再次确认删除 | 仅本次新建对话删除 | 13-https-test-deleted.png |
| 重启后重新登录选原样本 | 原消息保持 | 14-https-after-restart.png |
| 退出 | 登录页就绪，Session Cookie移除，读取会话401 | 15-https-logged-out.png |
| 打开图谱新概念、中英文和2D | 概念正文与关联保留 | graph-01-concept-zh.png至graph-03-concept-en-2d.png |
| 关联源码与3D切换 | TLS配置路径与阅读卡持续 | graph-04-source-en.png、graph-05-source-en-3d.png |
| 搜索/类型筛选 | 找到Docker前证书校验源码 | graph-06-filter-certificate-evidence.png |
| 最终课程状态 | 写明本地TLS已实测、仍有待补齐项 | graph-07-lesson24-status.png |

## 检查与未覆盖项

部署Node测试66/66、图谱37/37和前端生产构建通过；nginx -t真实成功，保留已有大chunk提示。此批无Java业务修改，未执行新的Maven或真实回答模型测试主张。

本地CA不是公网证书。本批未覆盖公网签发/续期、证书轮换、HSTS生产策略、mTLS、内部PG TLS、活动运行中升级、完整双租户审批或CI/容量验收。短期材料过期后必须重新生成，不能靠关闭校验延续成功。第24课仍进行中。
