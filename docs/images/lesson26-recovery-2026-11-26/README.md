# 第26课首批分层验收

真实工程用例`FormalWorkerRecoveryPgTest`启动独立正式Worker JVM并使用专用PG，双租户写入同ID资料；停止测试自有进程后原客户端明确503，不切内存；同端口新进程启动后原客户端读回旧资料并保持隔离，各租户新写入可经HTTP与PG直接读回。首轮1项直接通过，属于补覆盖，未伪造生产缺陷。相关九类回归43项通过，0失败/错误/跳过。embedding为demo确定性实现，无供应商请求；不冒充真实模型或HTTPembedding验收。测试只停止自己拥有的进程，不控制原课堂组。

只读恢复快照工具4项HTTP夹具测试与4项CI契约回归通过。图谱37项通过，生产构建成功（既有大chunk警告保留）。这些证据各有范围，不能相加称为43次模型请求或完整故障矩阵通过。

## 页面操作

本地5212工作区生产构建，独立浏览器上下文、个人进度未改变；工作区有原有未提交页面改动，不宣称为干净云端发布。

| 操作 | 预期与实际 | 截图 |
| --- | --- | --- |
| 打开第26课所选知识 | 进行中，Worker恢复范围与剩余故障边界可读，源码入口正确 | [中文](01-course-zh.png) |
| 切换英文 | 同课程英文内容保留 | [英文](02-course-en.png) |
| 切换2D | 所选内容保留，可定位FormalWorkerRecoveryPgTest | [2D](03-course-en-2d.png) |

这是课程阅读与入口验收，不是业务故障页面验收。静态预览认证后端未连通，若顶部出现REQUEST_FAILED如实保留；不能写整页服务就绪。本课后续仍需正式主服务/MCP/HTTPembedding的停机及恢复页面、真实模型等待故障、PG故障与预算UNKNOWN等综合演练证据。下一课材料与图谱继续维护，不将个人掌握状态自动设为完成。

## 真实页面演练的部分结果（2026-11-26）

使用此前固定0f25ac2干净JAR/前端5222、正式main/Worker和真实PG，独立课堂knowledge-maintainer账号。页面导入合成`lesson26-before`成功，Worker配置HTTP embedding；这是实际知识写入，未调用聊天模型。[基线](04-live-baseline.png)。

首轮控制助手使用旧PowerShell，因缺Get-FileHash在身份检查阶段失败，尚未停止Worker。换当前pwsh后，先核对JAR SHA256及Worker角色/PID27448，再仅停止该进程，点击“刷新连接与文档”，页面明确显示KNOWLEDGE_WORKER_UNAVAILABLE；[停机页面](05-live-worker-unavailable.png)，未伪装为空库成功。助手随后用同JAR/同端口恢复Worker，新PID17896，经身份检查和TCP18462可达确认，未重复启动其他角色。

恢复后读取旧原文与新写入的页面流程尚未通过：脚本在worker-restored阶段失败，重试登录实际HTTP503/AUTHORITY_UNAVAILABLE，main日志同期有授权数据库不可用；Docker查询仍在等待，不据此断言数据库容器已经停止。旧原文、新写入、embedding供应商与PG数据独立核对均待依赖恢复后继续。不将进程可连等同于完整业务恢复，也不把已有Java demo-embedding恢复用例冒充本次真实HTTPembedding页面通过。未删改数据库/旧实验组，脚本未完成的新资料不记作成功。
