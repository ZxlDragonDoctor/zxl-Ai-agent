# 第24课：干净源码与企业启动入口

可复现交付首先要知道“构建的到底是哪份源码”。工作区有未提交修改时，直接打包不能证明某个提交可部署。本项目新增 `scripts/lesson24/export-source.mjs`：先固定Git提交，再导出提交树，记录ZIP摘要及显式省略的子模块。它不复制本地配置、未跟踪文件或历史class。

## 导出与构建

```powershell
node scripts/lesson24/export-source.mjs --output target/fresh-source.zip --revision HEAD --omit-submodule zxl-image-search-mcp
```

输出必须是target中新文件；重复目标、越界、链接、已跟踪凭据名称和生成目录会拒绝。明确配置模板允许导出，仍需审查内容；名称规则不能替代内容密钥扫描。旧图片MCP子模块不属于正式知识MCP链路，省略必须显式记录其路径和固定提交，不能静默假装已构建。

在新目录解压后运行Maven打包与必要测试、`npm ci --ignore-scripts` 和前端构建。离线依赖缓存缺失时必须如实记录，不把“本机已有node_modules”当作锁文件安装成功。本机缓存并不等于依赖仓库可长期重现，镜像和制品保存仍待实施。

## 独立正式入口

`com.zxl.enterprise.EnterpriseAgentApplication`只扫描现代正式模块；使用单独的enterprise profile并拒绝身份降级或混入课堂profile。原入口及默认JAR入口保留。正式Research、知识、Skill、审批仍由已有Provider注册表接入。

旧Research和工具链只提供明确拒绝的兼容接口，避免课堂组件初始化RAG或调用外部模型。正式Skill配置还需要独立Gateway、Model、Verifier接口Bean；仅注册组合Runner会导致真实启动失败。这个问题通过依赖图刷新测试和实际启动失败记录复现后修复。

Boot JAR包含PropertiesLauncher，可显式选择企业入口：

```powershell
java -Dloader.main=com.zxl.enterprise.EnterpriseAgentApplication -cp target/zxl-Ai-agent-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher --spring.profiles.active=enterprise
```

命令用于说明入口选择，真实运行仍需显式环境、PostgreSQL、内部令牌和服务来源。不要把密钥写进命令参数或提交到Git。当前内部客户端要求同主机loopback，不能直接用Compose服务名替换。

也可使用 `node scripts/lesson24/launch.mjs --role main --jar target/zxl-Ai-agent-0.0.1-SNAPSHOT.jar`；worker和mcp角色同理。启动器先检查所选角色的显式环境，再用固定参数数组启动Java，不读取本地凭据文件。它不负责健康等待、容器编排或日志清洗；Java输出需由部署日志系统单独管理。

## 验收边界

源码导出、编译、自动测试、数据库迁移、实际启动、真实模型、浏览器、容器及重启恢复分别记证据。本机Docker引擎不可连接，不能声称容器已验收。启动成功也不代表审批、跨租户、业务链、回滚与持久数据均已验收。

[本批观察与截图](../images/lesson24-clean-2026-11-23/README.md) · [第24课完整任务](24-reproducible-deployment.md)
