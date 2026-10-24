# 第十二课：本地 PostgreSQL 实验运行说明

本页只说明怎样复现本课环境，不代替课程正文或验收报告。所有命令在仓库根目录的 PowerShell 执行。

## 1. 已验证的版本与边界

| 项目 | 本次实际使用 |
| --- | --- |
| Java | 17 |
| Spring AI 依赖基线 | 现有 M6 / Alibaba M6.1，不升级依赖 |
| PostgreSQL | 16.15，conda-forge Windows 包 `postgresql-16.15-he837cf3_0` |
| pgvector | 0.8.6，conda-forge Windows 包 `pgvector-0.8.6-h2466b09_0` |
| 数据库 | `127.0.0.1:15432/lesson12`，schema `lesson12` |
| 窄实验 API | `127.0.0.1:18132/ai/learning/knowledge` |
| 前端 | 现有 Vite 服务；知识库接口由 Vite 转发到 18132 |

这组 Windows pgvector 二进制与 PostgreSQL 16 配套。本次尝试 PostgreSQL 17 时存在包版本约束冲突，不能将其他版本的 `vector.dll` 直接复制过来假装兼容。

本课通过 JDBC 直接写向量和清单：事务范围明确，不依赖尚未核实的 M6 `PgVectorStore` 构造器或隐式事务行为。检索是精确余弦距离 `<=>` 基线，没有声明 HNSW/IVFFlat 性能收益。

## 2. 数据实际存在哪里

| 数据 | PostgreSQL 表或位置 |
| --- | --- |
| 文档身份、状态、生效版本、版本围栏、错误码 | `lesson12.knowledge_document` |
| 不可变原始 Markdown/文本、摘要、处理配置摘要、模型身份、版本状态 | `lesson12.knowledge_revision`，原文列 `raw_text` |
| 分块文本、UTF-16 原文位置、行号、标题路径、向量和模型/维度 | `lesson12.knowledge_chunk`，文本列 `text_content`、向量列 `embedding` |
| Flyway 迁移记录 | `lesson12.lesson12_schema_history` |
| 当前便携数据库数据目录 | 忽略目录 `runtime-data/pg-lesson12-data` |

`embedding` 使用 pgvector 的可变维度 `vector` 列，每行通过 `vector_dims(embedding)=dimensions` 约束验证。处理配置和版本摘要包含模型身份与维度；查询必须同时匹配当前模型身份和维度，避免把不同向量空间混合排名。

删除会取消生效指针并同步删除分块和向量；历史原文快照仍保留作教学审计。这不是合规要求下的完全擦除实现。

## 3. 准备 PostgreSQL

已有兼容 PostgreSQL + pgvector 实例时，直接创建独立实验数据库并配置第 4 节环境变量即可，无需使用便携部署。不要将测试指向生产库。

本次 Windows 便携方案使用官方 conda-forge 包。安装 [micromamba](https://mamba.readthedocs.io/en/latest/installation/micromamba-installation.html) 后，可按已验证包版本准备环境：

```powershell
micromamba create -y -p "$PWD/runtime-data/pg-lesson12" -c conda-forge --override-channels `
  'postgresql=16.15=he837cf3_0' 'pgvector=0.8.6=h2466b09_0'
```

下载的运行时、缓存、数据库目录不进入 Git。不同平台需要选择与该平台 PostgreSQL ABI 匹配的 pgvector 包，不能照搬 Windows build 字符串。

**只对一个全新的空数据目录执行一次初始化**。已有数据目录不要重建，启动脚本也不会删除或初始化数据：

```powershell
$env:PATH = "$PWD/runtime-data/pg-lesson12/Library/bin;$env:PATH"
initdb -D runtime-data/pg-lesson12-data -U lesson12_owner -W --encoding=UTF8 --locale=C --auth=scram-sha-256
./scripts/lesson12/start-postgres.ps1
createdb -h 127.0.0.1 -p 15432 -U lesson12_owner -W lesson12
psql -h 127.0.0.1 -p 15432 -U lesson12_owner -W -d lesson12 -c 'CREATE EXTENSION IF NOT EXISTS vector;'
```

口令由工具交互提示，不写入命令参数或提交文件。以上初始账号是本地实验管理员，并不代表已完成生产最小权限设计；正式部署应由迁移管理员安装扩展，应用账号仅获所需 schema 权限。

`start-postgres.ps1` 只启动指定的已初始化实例；发现该实例已运行时原样保留，端口被其他进程占用时直接报错，不停止或重置其他进程。

## 4. 配置本地环境

复制示例到忽略的 `target` 目录，并按自己的环境编辑副本：

```powershell
New-Item -ItemType Directory target -Force | Out-Null
if (Test-Path target/lesson12-db-env.ps1) { throw 'Local environment file already exists; keep and edit your existing copy.' }
Copy-Item scripts/lesson12/env.example.ps1 target/lesson12-db-env.ps1
. ./target/lesson12-db-env.ps1
```

环境示例会交互读取数据库口令。若该目标文件已经存在，先查看自己的副本，不要再次复制覆盖。本仓库实际运行使用的是自己的忽略副本，提交的示例没有真实口令或模型密钥。

变量包括：

| 变量 | 作用 |
| --- | --- |
| `LESSON12_DB_URL` | 必须是 `jdbc:postgresql:` URL；建议配置连接和 socket 超时 |
| `LESSON12_DB_USER` / `LESSON12_DB_PASSWORD` | 实验数据库凭据 |
| `LESSON12_EMBEDDING_MODE=demo` | 默认的离线确定性 8 维坐标，用于生命周期和界面演示，**不具有语义检索能力** |
| `LESSON12_EMBEDDING_MODE=http` | 使用真实兼容 HTTP Embedding 服务 |
| `LESSON12_EMBEDDING_ENDPOINT` | 真实模式必需，与你的区域/部署匹配的 HTTPS `/embeddings` 地址 |
| `LESSON12_EMBEDDING_API_KEY` | 真实模式必需，不从已提交配置推测或读取 |
| `LESSON12_EMBEDDING_MODEL` | 默认 `text-embedding-v3` |
| `LESSON12_EMBEDDING_REVISION` | 真实模式必需，显式记录供应商版本或自己的部署版本标签 |
| `LESSON12_EMBEDDING_DIMENSIONS` | 真实模式默认 1024；必须符合实际模型输出 |

模型未提供不可变版本号时，应明确说明这个标签的维护责任，不能把自定义标签当成供应商保证。端点、模型、版本标签和维度会共同影响模型身份；更换身份后需重新导入资料，旧向量不会与新模型混排。

真实 HTTP 协议参考[阿里云 Embedding 文档](https://www.alibabacloud.com/help/en/model-studio/embedding)。未提供 key、配置不合法或请求失败时会明确失败，**不会退回 demo 后报成功**。连接超时 5 秒、单次请求超时 30 秒，不自动重试。单次导入可能处理多个分块；这里不把单块超时称为整个导入的总截止时间。

## 5. 启动独立实验服务

```powershell
. ./target/lesson12-db-env.ps1
./scripts/lesson12/start-demo.ps1 -Port 18132
```

启动脚本先编译并生成独立依赖 classpath，再运行 `Lesson12Demo`。它主动启用 `lesson12-demo`，只注册本课 MVC 配置，不扫描原应用的 RAG、模型或 MCP；不会启动完整 8123 应用。

发现 18132 已有监听者时脚本报错，不会杀死现有服务。需要另一个实验进程时可指定其他端口，但前端默认代理仍指向 18132。

服务启动本身不调用模型；真实模式下执行导入或检索才调用供应商并可能计费。健康信息：

```powershell
Invoke-RestMethod http://127.0.0.1:18132/ai/learning/knowledge/status |
  Select-Object storage, embeddingMode, dimensions, vectorVersion, tenantId
```

服务固定租户 `demo-enterprise`，不从请求接受租户身份。这只是本地实验边界，不是认证、多租户授权或生产安全方案。

## 6. 自动化验证

```powershell
. ./target/lesson12-db-env.ps1
./scripts/lesson12/test.ps1
```

当前选择 22 项用例：文本 7、HTTP 协议替身 2、默认扫描隔离 1、故障响应 2、真实 PG 仓储 8、真实 PG + MockMvc API 2。HTTP 测试使用本地受控替身，不产生付费模型调用。故障响应回归覆盖所有读写入口：数据库连接失败返回 `503 STORAGE_UNAVAILABLE`，当前资料状态待连接恢复后确认；普通操作失败返回 `500 OPERATION_FAILED`。失败文案不一概承诺旧版本仍然可读取。

真实 PG 用例包括重复导入零计算、更新、删除、租户/模型/维度隔离、Embedding 故障、发布 SQL 故障回滚、旧任务围栏、删除防复活，以及两个独立 Java 进程读取同一原文和向量。故障注入约束限定本次随机测试租户，使同一数据库上重复运行仍可通过；无需清空历史数据。

数据库不可用会失败，不能通过跳过或自动替换成内存声称 PG 通过。结果位于 `target/surefire-reports`。这些测试不证明真实模型召回质量、真实 WebGL 页面效果、生产容量或高可用。

## 7. 独立的重启与页面验收

两次不同 JVM 测试只证明应用进程之间的持久性，不等同于数据库进程重启。数据库重启应在页面操作间隙安排：记录当前文档版本/块数/检索结果，正常停止指定 PG 实例，再使用同一数据目录启动并复查，不删除目录或迁移历史。

页面应逐项检查空列表、示例导入、重复导入、详情与原文分块、修改导入、检索、删除、错误显示、语言切换和学习入口，并为操作后状态保留截图。截图验收、数据库进程重启和真实模型调用的实际结果以本批验收记录为准，本运行说明不把准备步骤记为已通过。
