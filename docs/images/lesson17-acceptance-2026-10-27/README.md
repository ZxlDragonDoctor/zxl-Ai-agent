# 第十七课阶段证据：基础代码与知识入口

日期：2026-10-27。**这是进行中阶段记录，不是第十七课整体页面验收。** 单身份真实登录与退出已验证，worker/MCP、真实模型和两个独立浏览器身份的业务链仍待集成验证。

## 自动测试

[`backend-tests.json`](backend-tests.json) 从本轮明确选择的 10 份 Surefire XML 摘录：74 项、0 失败、0 错误、0 跳过。包含真实 PostgreSQL 16.15、独立 JVM 会话恢复、实际锁等待、MockMvc 和临时 JSON 文件；部分测试继承认证用例，执行数不等于独立场景数。没有打开真实 HTTP 端口，也没有调用真实模型。

图谱测试 34 项通过；`vue-tsc + Vite build` 成功，保留既有大 chunk 警告。完整命令和分层边界见 [17-implementation.md](../../learning/17-implementation.md)。

正式接线修复后新增汇总 [`backend-formal-tests.json`](backend-formal-tests.json)：**13 类 97 项、0 失败/错误/跳过**。包含配置/default profile、可信 Handler 和正式会话 Controller；97 是新的定向回归快照，不与早期 74 累加。前端身份 10 项、知识相关 40 项由主任务重跑通过。

真实 loopback 8123 主应用与 PG 的 **12 步 HTTP** 证据见 [`live-http-scope.json`](live-http-scope.json)：两组独立 Cookie 容器验证本人会话与异租户 404、关闭旧 GET、员工写权限拒绝、未迁移 handler 503、退出与测试会话清理。该证据不是两个独立浏览器 profile，也没有调用模型。

## 本轮实际页面操作

页面：`/learning`，116 个节点、164 条关系、28 课。通过实际浏览器按钮操作，截图为当时窄视口的原始画面，不能据此声称已完成桌面尺寸、全部图谱按钮或正式业务页面验收。

| 截图 | 操作 | 预期与实际 |
| --- | --- | --- |
| [01](01-course-directory.jpg) | 打开课程目录 | 28 课及新增证据可选择，个人掌握仍 0/28 |
| [02](02-lesson17-reading.jpg) | 选择第十七课并收起目录 | 显示“实现与验收中”、已实现基础与未覆盖范围；个人仍未开始 |
| [03](03-lesson17-one-hop.jpg) | 1 跳 | 所选节点及直接邻域，共 11 节点、14 关系，阅读卡保留 |
| [04](04-lesson17-two-hops.jpg) | 2 跳 | 扩展为 28 节点、37 关系，阅读卡保留 |
| [05](05-lesson17-two-dimensional.jpg) | 3D → 2D | 节点与关系同时显示，所选课内容可读 |
| [06](06-lesson17-english.jpg) | 英文切换 | 课程、进行中状态、概念、边界与关系切换英文 |
| [07](07-authorization-code-evidence.jpg) | 沿关系进入授权源码证据 | 显示 `AuthorizationService.java` 与 Decision/gate 概念 |
| [08](08-lesson18-task-boundary.jpg) | 返回第十七课，再沿先修进入十八课 | 第十八课仍计划状态，完整六单元任务路径可读 |
| [09](09-progress-restored.jpg) | 中文切回、恢复备份、重新导出 | 导出 JSON 深比较相等，原掌握、笔记和阅读数据恢复 |
| [10](10-live-anonymous-login.jpg) | 正式主应用启动后刷新身份 | 匿名状态正常，登录按钮可用，旧 NOT_FOUND 消失 |
| [11](11-live-wrong-password.jpg) | 合成账号提交错误密码 | 进行态显示连接中，表单禁用 |
| [12](12-live-wrong-password-rejected.jpg) | 等待错误密码终态 | 拒绝提示、密码清空、表单可重试 |
| [13](13-live-authenticated-scope.jpg) | 正确密码重试 | 显示实际合成 workspace/employee，业务列表仍在加载 |
| [14](14-live-unmigrated-handler-denied.jpg) | 等待业务列表终态 | 未迁移 Agent 类型接口拒绝，页面显示操作失败，新建/发送不可用；不是业务成功 |
| [15](15-live-logout.jpg) | 退出账号 | 返回匿名登录，主体与业务区域卸载 |
| [16](16-live-public-learning.jpg) | 刷新公开图谱 | 公共框架出现，课程模块仍在加载 |
| [17](17-live-public-learning-ready.jpg) | 等待课程模块完成 | 匿名可见116/164图谱，认证服务错误已消失；再次导出个人数据与原备份相等 |

逐步时间、操作、预期和实际保存于 [`observations.json`](observations.json)。测试备份只保存在忽略的 `target/lesson17-progress-before.json`、`...after.json`，没有提交个人学习数据。未点击修改掌握状态或笔记。

## 已观察到的限制与重试

- 最初 01–09 截图时主应用尚未切换，认证栏显示 `NOT_FOUND`；10–17 在正式主应用启动后重跑，身份服务已接通。旧失败保留，不能将旧图当登录成功；正式 Agent 业务仍未接完，14 的拒绝态也不是模型成功。
- 本轮进入旧页面时曾出现 3D 创建失败的 2D 降级提示。内容更新后重新建立 3D 并正常操作；没有将一次重建成功写成渲染缺陷已经修复。
- 英文关系按钮受 CSS 大写样式影响，第一次按整段可访问名称定位失败；按已观察的按钮内容定位后成功跳转，保留此过程。
- 浏览器当前默认是窄视口；记录到的 DOM 可用宽度为 375/390。临时视口覆盖已 reset。后续正式页面验收仍须补充桌面、移动、连续操作与独立身份。
