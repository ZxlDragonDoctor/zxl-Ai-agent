# 干净源码导出

在仓库根目录执行：

```sh
node scripts/lesson24/export-source.mjs --output target/source-new.zip --revision HEAD
node --test scripts/lesson24/export-source.test.mjs
```

本项目正式知识MCP不依赖旧图片子模块，可显式执行：

```sh
node scripts/lesson24/export-source.mjs --output target/formal-source-new.zip --revision HEAD --omit-submodule zxl-image-search-mcp
```

默认仍拒绝全部gitlink。只有显式列出的gitlink可省略，manifest记录其路径与提交；输出ZIP不包含该目录。不存在的省略路径拒绝，所有symlink仍拒绝。此产物只用于不依赖省略模块的正式知识链路，不能宣称旧图片功能也在交付范围内。

revision默认HEAD，先解析为不可变commit，再执行git archive。输出ZIP及同名`.manifest.json`包含commit、ZIP SHA256和范围说明。工作区修改、未跟踪文件及本机配置不参与导出；脚本不构建、不解压、不删除文件。输出限定为仓库根target下新ZIP，不覆盖ZIP或manifest，不接受子目录或越界路径；拒绝target链接/junction。

导出前检查提交树，发现application-local.yml/yaml/properties、.env及其变体、常见私钥/证书容器文件名、生成目录或Git链接/submodule即拒绝。明确的.env*.example/.template模板允许导出（如.env.production.example），操作者仍须审查模板内容。错误只输出固定码，不输出文件内容。此检查按名字保守拦截，不扫描未知文件内容，不能替代密钥扫描或保证历史从未含密钥。Git archive依照提交中的export-ignore属性；manifest中的trackedEntries是Git树条目数，并非ZIP展开条目数。

Windows临时Git仓库测试覆盖dirty/untracked排除、commit和摘要、旧commit、无效revision、覆盖/越界、tracked凭据名、生成目录及target junction。测试只生成独立临时夹具，不删除已有目录。

产物只证明选定提交的源码导出，不证明构建、数据库、模型、容器或部署成功；这些必须分别验收。ZIP放target供本机验收，不把整个源码ZIP再次提交进Git。
