# 第24课：持续交付门禁、真实PG与产物追溯

CI是每次提交在干净环境重复执行门禁；它不等于自动部署，也不等于模型效果评估。本项目把Java/真实PG和前端/交付工具分成两个job。每个结果都必须对应固定commit，不能用本地脏工作区的通过替代云端证据。

## 测试范围先于通过数量

`java-test-inventory.mjs`按源码发现测试，新测试默认进入CI。当前174个命名文件中164进入CI，10个旧完整应用/付费模型/外部网站演示以精确路径和理由记录为人工执行。盘点会拒绝Surefire漏发现的命名、未分类完整SpringBootTest、额外执行白名单及隐式跳过。

源码文件数不是JUnit用例数，也不是覆盖率。实际tests/failures/errors/skipped来自Surefire XML。Windows专用旧终端工具在Linux明确验证COMMAND_START_FAILED，在Windows仍验证真实输出和退出码；没有用平台skip掩盖行为。课堂lesson9 H2对照单独注明，正式PG测试由固定pgvector容器提供独立数据库，不退回内存。

CI没有供应商模型密钥。确定性模型/协议夹具用于验证身份、预算、状态和错误；真实模型效果仍按第22课的独立案例和证据验收，不能因CI绿色就宣称LLM质量通过。

## 当前流水线的边界

官方Actions由完整commit锁定，工具链记录Java17.0.21.1+2、Node22.22.2和PG镜像digest。runner标签ubuntu-24.04会更新，所以这不是位级环境复现保证。仅contents:read权限，不使用pull_request_target，不携带生产部署凭据。

旧图片MCP gitlink缺少.gitmodules曾使checkout action清理失败。现通过公开HTTPS获取GITHUB_SHA并核对HEAD，不遍历该未初始化模块；保留原链接，明确CI不验证旧图片MCP。正式知识MCP测试仍纳入。

Java先盘点，再mvn -Penterprise-ci verify。前端用lockfile安装，自动发现所有Node测试并构建；部署和评估工具另外执行。失败不continue-on-error，也不因为云端失败就把用例挪进排除列表。

## 这次云端暴露的问题

第一轮在取源码阶段失败，尚未执行测试。随后Java实际执行813项，0跳过，发现两个测试切片问题和一个期限边界问题：缺凭据负例受合法CI环境fallback影响；控制器slice缺少新增预算配置；远端工具失败事件发布期间总期限到期后，旧逻辑仍保留局部码。

前两项通过环境隔离和真实预算配置修复测试，保留原安全断言。ResearchRunner在发布远端失败后重新检查总截止；提前发生的MCP局部超时仍保留原码。新增测试确认确实进入失败发布后才跨截止，真实PG/SSE夹具保持1000ms总预算，延后RPC结果以明确控制场景，不通过放大预算消除失败。局部超时与总期限是不同事实。

## 产物不是打包整个工作区

`ci-manifest.mjs`核对HEAD、受跟踪源码洁净，记录指定JAR/dist文件的字节数、SHA256及真实XML根计数。它不构建、不打包、不读取环境变量，也不扫描JAR内容或证明报告新鲜度；必须结合干净checkout和实际执行顺序。只上传指定产物和报告，禁止整个target、私钥、运行数据或本地配置。

下载后重新计算ZIP摘要，并按manifest逐文件验证。摘要只能检测不一致，不能独自认证来源，不等于签名/SLSA证明。还需核对仓库、run、head SHA及执行结果。进入运行环境前仍须检查schema、TLS、身份、预算和业务数据，已有有序发布/回退工具属于下一层门禁。

[工作流与操作](../../scripts/lesson24/continuous-integration.md) · [测试盘点](../../scripts/lesson24/java-ci-README.md) · [产物记录](../../scripts/lesson24/ci-manifest.md) · [实际run与截图](../images/lesson24-ci-2026-11-24/README.md)

## 面试复盘

为什么测试文件数不能当作覆盖率？为什么真实PG测试仍不证明模型效果？为什么一个green job不能证明已发布？为什么checksum与可信来源是两件事？如何证明一次CI失败修复保留了原约束，而非缩小测试范围？
