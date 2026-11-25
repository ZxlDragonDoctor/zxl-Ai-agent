# 第24课：云端持续集成验收

验收提交：`78c0e534345c01e50e79854a805410376e92edbc`。实际 [GitHub Actions run 45429812972](https://github.com/ZxlDragonDoctor/zxl-Ai-agent/actions/runs/45429812972) 两个 job 均成功。记录日期为北京时间2026-11-25，截图目录沿用本批开始日期。

## 分层结果

- Java/真实 PostgreSQL + pgvector：814项，失败0、错误0、跳过0。164份Surefire XML；174个测试源文件中164进入CI，10个旧人工演示精确列明理由，文件数不等于用例数。
- 前端：115项通过、0失败、0跳过；交付/评估工具：136项通过、0失败、0跳过。生产构建成功。
- 本地最终图谱回归37项通过，生产构建通过；仍有大chunk警告，未作性能通过声明。
- 此批不使用付费模型密钥，不代表真实模型质量或完整业务发布验收。CI上传产物不等于自动部署。

## 页面逐状态验收

使用独立浏览器上下文，不修改原有个人学习状态和笔记。本地5212为当前工作区构建的预览（含原有未提交页面改动），不冒充云端发布的前端。

| 操作 | 预期与实际 | 截图 |
| --- | --- | --- |
| 打开早期运行 | 展示真实执行进度，该运行Java随后失败，不能当成功证据 | [01](01-github-run-progress.png) |
| 打开最终运行 | Success、两个job、三个产物及摘要均可见，符合预期 | [02](02-github-run-success.png) |
| 打开新增CI概念 | 中文知识内容及关联可读，符合预期 | [中文](graph-01-concept-zh.png) |
| 切换英文 | 同节点显示英文内容，符合预期 | [英文](graph-02-concept-en.png) |
| 切换2D | 同一所选内容保留，符合预期 | [2D](graph-03-concept-en-2d.png) |
| 点击关联工作流源码 | 阅读卡显示工作流路径，符合预期 | [源码](graph-04-source-en.png) |
| 切回3D | 所选源码内容仍可读，符合预期 | [3D](graph-05-source-en-3d.png) |
| 搜索CI产物并筛选源码类型、点击结果 | 一节点零条可见边；阅读卡展示manifest源码及全局关联，符合过滤语义 | [筛选](graph-06-filter-ci-evidence.png) |
| 打开第24课 | 新CI结果与公网续期/完整业务待补齐边界同时展示，符合预期 | [课程](graph-07-lesson24-status.png) |

## 保留失败与修复

第一轮取源码失败（缺少旧gitlink配置）没有执行测试，见 `initial-checkout-failure.json`。第二轮Java813项中2失败1错误、0跳过；修复了CI环境凭据隔离、预算测试切片依赖和工具失败发布期间跨总期限的竞争。新增确定性回归，保留原安全断言、真实PG测试及1000ms总预算，不用排除失败用例换取绿色。

截图是本地课程内容验收；未宣称它们是早期云端SHA的发布页面。课程/图谱提交 `9f1c5ed5ac94f202106a65845710433d354be741` 已再次执行 [run 45432538665](https://github.com/ZxlDragonDoctor/zxl-Ai-agent/actions/runs/45432538665)，两个job成功，Java814、前端115、交付工具136，均0失败/0跳过。见 [最新成功截图](03-github-final-graph-run-success.png) 和 `9f1c5ed-final-run.json`。这次只重新核对云端结果与计数；逐字节下载核验仍精确属于下述78c0e53产物，不混用两个版本的摘要。

## 产物核验

`78c0e53-artifact-verification.json`记录下载ZIP与GitHub摘要对照、JAR及全部21个dist文件逐项字节/SHA256对照、164份XML与manifest哈希及计数对照；两个 `78c0e53-*-manifest.json`来自对应云端产物。ZIP/JAR不入仓库，下载地址不入证据。CI产物只保留7天，记录的摘要长期保留。旧无前缀frontend文件属于第二轮 `cc18ba8` 的成功前端job，不能证明当轮Java通过。

摘要用于一致性核对，不等于签名或来源认证；来源还需结合仓库、run、commit与job结果。未验收公网证书续期、完整业务发布、全量模型评估及容量。
