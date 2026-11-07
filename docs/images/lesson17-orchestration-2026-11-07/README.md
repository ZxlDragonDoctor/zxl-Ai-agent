# 正式多 Agent 页面验收 · 2026-11-07

本轮使用 loopback 主应用8123、知识worker18132、MCP worker18135和真实PG15432，前端5174。甲乙是预先创建的合成验收账号；截图没有密码。一个浏览器profile顺序切换，不等于两个独立profile并发验收。没有修改个人掌握或笔记。

| 操作/状态 | 预期与实际结果 | 截图 |
| --- | --- | --- |
| 匿名进入 /multi-agent | 显示登录入口，不装载旧全局工作台 | 01-login-required.png |
| 甲登录 | 本人列表初始为空，无其他运行 | 02-own-workspace-empty.png |
| 提交90秒研究目标 | 服务端创建ID，显示实际规划状态 | 03-submit-running.png |
| 首轮真实MCP失败 | 实际返回MCP_DEADLINE_EXCEEDED，页面失败，无伪造回答；旧版展开过长的计划JSON也保留 | 04-real-tool-failed.png |
| 提交另一个目标后取消 | 确认已取消，但仍提示真实调用资源清理中 | 05-cancel-requested.png |
| 不手工刷新，等待自动轮询 | 清理完成提示消失；仍为已取消，不能被旧poll覆盖 | 06-cancel-cleaned.png |
| 切换英文 | 页面和取消确认切换英文，业务目标保持原文 | 07-cancel-english.png |
| 修复混合检索预算后，原问题与90秒预算复验 | 重新规划；未改成keyword、未提高协议预算 | 08-retest-start.png |
| 自动进入终态 | 规划1次、研究2次模型调用；工具事件与最终答案显示，计划/任务输出默认折叠。文件名running为采集时命名，截图实际已完成 | 09-retest-running.png |
| 展开真实工具完成事件 | 请求hybrid；真实HTTP 1024D Embedding，vector/keyword均OK，1452ms、degradations为空；2条当前来源，topK3不足明确标注 | 10-real-hybrid-tool-result.png |
| 折叠工具并阅读最终结果 | 仅甲标记AURORA-A-20261029及C1/C2原文行号；无乙标记；正文未添加知识外步骤 | 11-answer-with-citations.png |
| 选择本人取消记录→刷新运行 | 显示该ID已取消，实际模型调用1次，无旧成功答案残留 | 12-select-and-refresh-history.png |
| 刷新列表→退出 | 私有目标、运行、工具与答案清空，返回登录 | 13-logout-clears-private-view.png |
| 乙登录→刷新列表 | 乙列表为空，无甲的三个运行与答案；随后退出 | 14-b-workspace-isolated.png |
| 图谱筛选“正式多Agent”→打开新增证据 | 119个实体/167关系；筛选到课程和证据，3D阅读卡正确显示来源 | 15-graph-new-evidence-3d.png |
| 沿关联返回17课→清空筛选→2D→1跳 | 显示14节点/17关系，课程摘要与新增证据同步 | 16-graph-lesson17-one-hop-2d.png |
| 2跳→英文 | 显示31节点/40关系及英文课程摘要 | 17-graph-two-hop-english.png |
| 中文→恢复验收前完整备份→再次导出 | 备份JSON深比较完全相等；个人记录不入Git | 18-progress-restored.png、progress-preservation.json |
| 提交屏障修复后重启主应用→提交同题 | 新ID、90秒预算，实际规划/研究 | 19-final-version-start.png |
| 最终版本真实复验 | vector路线6231ms失败，keyword成功，工具6684ms明确VECTOR_CHANNEL_FAILED降级并返回甲原文；随后研究回答失败，无最终答案。不得将此前成功外推为本轮通过 | 20-final-version-tool-degraded-failure.png |
| 最终图谱边界更新→阅读卡→再次恢复备份 | 页面明确120项回归/10项HTTP通过、最终模型回答仍失败；再导出深比较仍相等 | 21-final-graph-boundary.png |

首轮运行`85cac3a3-eb62-4a9e-943a-fb254e6df44e`失败。取消运行`0ac07ab7-6ca4-478f-ae79-bf9ac0c9a36a`完成资源清理。复验运行`6a1cc0df-7e26-48e1-b378-b393eb9b8bc5`成功。工具traceId为`7caecc0e-7a54-42a8-a365-dda51827f70e`。工具返回reranker=disabled，不能称为实际模型重排验收。

混合检索预算修复针对独立复现的问题；本次成功不证明首轮超时只有这一个原因，也不是压测或稳定性保证。HTTP跨账号404/CSRF检查与自动化证据分别见本课evidence目录。运行报告当前在有界内存中，重启不恢复；本页不是持久事件重放或SSE验收。

最终主应用PID30560上的运行`cfa3a0c1-9cdf-4959-8b46-9501c47d729c`失败，工具traceId为`ffed5666-6953-4a2d-84a1-30dcf4e2fcaa`。它证明实际向量慢时关键词保留了预算，没有证明回答阶段成功。页面仅有通用EXECUTION_ERROR；当时服务端未记录足够的阶段诊断，不能据此断言外部模型、截止或提交屏障是哪一个根因。该失败保持未通过，后续诊断另记。

本批前端实际重跑身份/正式API/取消代际24项、图谱37项，均0失败/跳过；生产构建退出0，保留既有3D分包大于500kB警告。图谱经运行时validateCatalogue校验：ID唯一、端点存在、先修无环。日志为本地忽略目录`target/lesson17-orchestration-{frontend,graph,build}-20261107.log`。
