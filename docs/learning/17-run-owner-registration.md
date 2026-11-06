# 第十七课：运行归属必须先于执行

## 概念

运行 ID 是定位符，不是权限。正式提交先由服务端创建 UUID，在 PG 中保存 tenant + subject 的 owner，再允许排队、调用模型和生成报告。用户只能提交业务目标，不能从 JSON 指定 ActorRef 或归属。

## 工程实现

`AuthorizationService.registerResourceAuthorized` 复用 sqlGate：当前 agent.execute、knowledge.read 的 Decision、拒绝审计和 owner INSERT 共用连接/事务。业务保存点使重复插入回滚 owner 写入，同时保留已生成的实际 Decision；没有在授权 gate 内再调用另一个 JDBC 事务。

`ScopedMultiAgentService.submit` 的可信 Factory 先登记 owner，再构造 ActorBoundary/TrustedRunContext。若 registry 容量拒绝而 submitScoped 向外抛错，服务只补偿本次未派发 run 的 owner，按 runId + tenant + subject 精确匹配；保留审计，不能删除其他人的 owner。已经返回运行 handle 的业务失败不走该补偿。

状态列表从当前有界 registry 取最多200个正式运行 ID，再用一条参数化 PG ANY 查询筛出当前账号拥有的候选，每个实际视图还要完整发布授权。不会先加载全部历史 owner 再逐条开启 PG gate；没有运行时也重新解析当前认证，不能因空列表跳过失效身份检查。

## 验证

`AuthorizedRunRegistrationPgTest` 4项真实 PG 测试：owner/Decision 同事务提交、重复登记不覆盖且保留审计、撤销后零 owner、未派发补偿限定 owner，以及候选有界查询。最初缺API编译失败；一次审计断言误把不带run_id的独立requireOwned审计算入，修正计数后重跑通过，原日志保留。

`ScopedMultiAgentServicePgTest` 4项使用真实授权和真实编排器，工作角色输出为确定性替身，覆盖同租户其他用户/其他租户不可读不可取消、实际租约清理、撤权中性收尾、容量失败只清理本次owner。这不是实际模型或数据库工作流持久恢复的证明。
