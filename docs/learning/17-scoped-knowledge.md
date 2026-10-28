# 第十七课：把固定演示身份升级为可信知识范围

## 从接口参数到权限范围

之前课堂的 `alice/bob` profile 用于演示，不是登录认证。正式 worker 的 `ScopedKnowledgeFactory` 为每个可信 `ActorRef` 创建独立服务：知识按 tenant 查询，事实记忆按 tenant 与 subject 查询，不把用户输入或模型提供的字段当身份。

记忆提议还必须核对会话 owner、真实用户消息 ID 和原文。只拥有一个 tenant 不能替别人确认偏好。回答在来源读取、实际模型请求和最终发布时，组合检查知识权限与会话归属。

`db/lesson12/V3__authenticated_identity_scopes.sql` 扩展身份列，保留 V1/V2 迁移及既有数据。正式 PG 身份支持 150 字符，文档 ID 支持 120 字符；这不是迁移到新的内存范围。

## 独立知识服务

`FormalKnowledgeWorker` 只显式装配 scoped Controller，不扫描旧课堂入口。服务请求需要独立 Bearer 凭据，以及绑定方法、规范查询和原始正文摘要的单次票据。消费后仍检查实际操作时的当前权限；浏览器 Cookie 和 profile 不能代替内部协议。

embedding、rerank、answer 的 HTTP 适配器在实际 `sendAsync` 启动时检查权限，远程等待在事务外。服务把完整响应缓冲在 1 MiB 上限内，刷新文本 writer 后，再授权准入。实际 socket 写入在 PG 锁外，避免慢客户端阻塞撤销。已经准入并开始发送的字节不能撤回。

## 已验证与未验证

2026-10-28 本轮 18 类 92 项整合回归零失败、错误或跳过。其中 scoped 知识 7 项、worker 认证 12 项、规范请求 2 项、发布 4 项、独立 JVM worker 1 项：真实 PG 隔离、中文长身份、同 ID 不同租户、无票/重放拒绝、来源校验、发布前撤销、超大响应和慢 socket 均有针对性测试。

独立 worker 使用临时端口与合成资料，结束后关闭。HTTP 模型适配器测试使用本机受控服务；没有据此声称已调用收费模型。完整日志：本地 `target/lesson17-integration-stage.log`，发布红阶段为 `target/lesson17-worker-publication-red.log`。正式 8123→18132→18135 的业务集成、真实模型与浏览器按钮验收仍待完成。课程状态保持进行中。

## 主应用内部客户端

`ScopedKnowledgeClient` 固定连接显式 `http://127.0.0.1:port`，不继承系统代理、不接受重定向，也不转发浏览器 Cookie 或 Bearer。它保留原始 JSON 字节、规范查询排序后签发一次票据；票据签发和实际 HTTP 启动是独立短事务，避免嵌套授权锁。

传入的 Java 权限列表与路由知识权限合并成一个 `gateAll`。会话和运行归属不能作为模型参数。响应通过有界 subscriber 接收至 1MiB，完整正文共享截止时间；取消标志在无正文返回时仍能结束等待。没有自动重试，以免重放已消费的票据。资源不存在和不可见统一为 404。

新增客户端 PG 7 项、Controller 4 项与契约 2 项共 **13 项通过，零失败、错误、跳过**，日志 `target/lesson17-gateway-maven-green.log`。先在真实 PG 用例复现不可见资源返回 403，再修正为 404。测试同时核对原始正文、票据篡改、双租户同 ID、未知身份字段零出站、撤销、慢正文、超大响应、额外归属权限及取消信号；服务端使用本机受控 HTTP 与真实 PG，不替代常驻正式 worker 的整体接线验收。
