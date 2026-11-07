# 第十七课实战：事实记忆与 Skill 撤权

事实记忆需要可核对的来源。正式对话页从已保存的会话快照里列出符合白名单的用户原话，将会话 ID、消息 ID 和原文交给 worker。服务端根据当前登录身份核对会话归属、消息角色和原文。用户确认后，事实才进入召回。更正选择同一类偏好的另一条消息，更新后重新进入待确认。

撤权要看发生在哪一步。记忆提议在读到可信来源后，还要通过 PostgreSQL 的保存检查。Skill 可以在权限允许时启动研究，等待外部结果期间释放短事务，发布建议和草稿前再核对权限。已授权的本地提交与撤权按数据库顺序判定。撤权先提交时，后续保存或发布会被拒绝。

| 阅读代码 | 核对行为 |
| --- | --- |
| [FactMemoryPanel.vue](../../zxl-Ai-agent-fronted/src/components/FactMemoryPanel.vue) | 从正式历史选取原话，提交提议、确认或更正。 |
| [ScopedKnowledgeFactory.java](../../src/main/java/com/zxl/zxlaiagent/knowledge/scoped/ScopedKnowledgeFactory.java) | 从会话仓储核验原话，携带当前主体和会话权限进入记忆事务。 |
| [ScopedSkillRunner.java](../../src/main/java/com/zxl/zxlaiagent/skills/ScopedSkillRunner.java) | 分步检查权限，在发布边界拒绝迟到的结果。 |
| [FormalSkillRevocationPgTest.java](../../src/test/java/com/zxl/zxlaiagent/skills/FormalSkillRevocationPgTest.java) | 在研究等待和实际终态入队前暂停，提交真实撤权，核对输出与审计。 |

练习时先用合成账号在企业知识对话中分别发送「我偏好中文回复」与「我偏好英文回复」。从已保存历史选中文原话，提议后先检查实际召回，再确认并重查。选择英文原话更正，观察版本从 v2 到 v3 待确认，再确认成 v4。退出后用另一账号检查该事实是否可见。

面试时可以解释为什么「已读到来源」与「已获准写入」是两个独立事实。用同一个时间线说明 Skill 为什么要在开始、保存和发布分别检查权限，以及如何用两个线程稳定复现撤权竞争。
