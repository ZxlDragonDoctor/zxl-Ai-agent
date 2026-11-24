# 第24课：企业工程CI入口

CI将固定提交在干净GitHub runner上重新测试和构建，输出可校验产物；它不等于部署、业务验收或真实模型评估。`.github/workflows/enterprise-ci.yml`在master相关源码变更、PR及手动触发时运行，两个独立job分别检查Java/真实PG与前端/交付工具。

Java先执行 `node scripts/lesson24/java-test-inventory.mjs --check`，再执行 `mvn -B -ntp -Penterprise-ci verify`。当前174个命名测试文件中164默认纳入，10个旧完整应用/真实网络演示精确排除并记录原因。新测试默认加入；命名不被Surefire发现、未分类SpringBootTest或静默skip会被门禁拒绝。盘点不是JUnit执行结果，实际数由Surefire XML记录。

PG服务使用固定pgvector镜像digest和独立临时数据库。job内数据库口令及内部token均为公开标识的合成CI值，不是生产配置；没有注入模型供应商密钥。课堂H2对照测试仍有保留并注明，不能把其通过称为真实向量库验证。CI测试身份可管理临时数据库，生产运行角色隔离另有运行证据。

前端job使用lockfile执行npm ci，自动发现全部Node测试，再运行生产构建；交付/评估工具测试也独立运行。没有把测试失败改成continue-on-error。官方Actions由完整commit固定，来源tag和日期见ci-actions.json；Java17.0.21.1+2、Node22.22.2及PG镜像也明确固定。GitHub runner标签ubuntu-24.04不是固定磁盘镜像，不能宣称每次字节级环境完全一致。

成功产物只上传显式JAR/dist和manifest，Surefire XML作为单独报告保留。manifest核对HEAD、受跟踪源码洁净和commit，记录已有文件字节数、SHA256及真实报告计数；不打包工作区、不扫描JAR秘密、不证明报告新鲜度或业务/模型正确性。禁止上传整个target或私密环境、CA/私钥、运行数据。每次run和attempt的产物名分开，保留7天。

工作流只读contents权限，不使用pull_request_target或部署凭据，checkout不保留Git凭据。它不会发布线上应用、覆盖数据库、自动迁移或自动回退。下载产物后仍需按已有部署门禁核对来源、schema、TLS、身份和业务证据，再推进到目标环境。

实际GitHub run状态、计数、产物及截图须另外记录；工作流文件和注入runner测试存在，不能代替云端实际执行通过。
