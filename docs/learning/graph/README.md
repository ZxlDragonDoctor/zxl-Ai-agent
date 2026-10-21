# Agent 学习知识图谱：建模与维护

本图谱把本仓库的 Agent 学习路线、概念、源码和验收证据组织成可查询的属性图。页面入口为 `/learning`，数据目录是 `zxl-Ai-agent-fronted/src/learning/graph/`。它是课程阅读和项目证据索引，不是线上 Neo4j 数据库，也不会被 Agent 当成 RAG 检索源。

## 建图要回答的问题

动态图谱的操作、探索状态与物理布局实现见 [Browser 风格动态图谱](browser-presentation.md)。图谱中也收录了“动态图谱与探索状态”概念节点，记录这套交互怎样由项目代码构建。

先确定图谱的使用者和问题：学习者要知道一项工程能力依赖什么、在哪段源码实现、当前工程做到什么边界、下一步读哪份课程记录。每个节点和关系都必须能回答其中至少一项。不要先为了“图很密”添加没有来源的连接。

## 实体、标识与关系

| 类型 | 表示什么 | 示例 |
| --- | --- | --- |
| `topic` | 课程阶段 | 单 Agent 执行链路 |
| `lesson` | 可完整阅读的课程卡 | `lesson-07` |
| `concept` | 可跨课程复用的机制 | 取消、幂等、RAG、知识图谱建模 |
| `evidence` | 仓库内的代码目录、课程记录或验收依据 | `evidence-runtime` |
| `source` | 外部参考项目或书籍 | `source-shannon` |

节点 ID 一旦发布就保持稳定，标题可更改。ID 应表达实体身份，不应由显示标题即时拼接。合并实体时保留旧 ID 到新 ID 的迁移映射，避免用户本地笔记和进度丢失。

边有稳定 ID、起点、终点、关系类型和理由。当前类型包括：

- `contains`: 阶段包含课程，方向为阶段 → 课程。
- `prerequisite`: 先修课程 → 后续课程；此类边必须构成有向无环图。
- `related`: 两个知识点有关；可以成环。
- `implements`: 课程 → 代码证据。
- `documented-by`: 课程或概念 → 课程记录/设计文档。
- `documents`: 来源 → 项目中的阅读导读。
- `informed-by`: 项目概念 → 外部参考来源。

边的方向和类型有语义，不要把所有关系压成一个“相关”标签。每条非结构性关系都应有简短理由，使读者明白它为何存在。

## 数据与验证

`catalogue.json` 是人工维护、版本控制的静态知识库。课程卡包含双语标题和摘要、概念、项目映射、练习、验收边界、面试问题与源文件路径；每课的 Spring Boot 类比集中放在根级 `springBootAnalogies`，通过课程稳定 ID 关联，避免把课程正文对象和后端工程类比字段耦合。图谱建模、校验、查询、Neo4j 映射和 GraphRAG 边界也作为概念节点。课程事实从路线与实现记录整理；外部参考的提交和入口见 `docs/learning/reference-guide.md`。

前端载入时会检查 schema 版本、节点 ID 唯一性、已知节点/关系类型、双语标题和摘要、关系端点、关系 ID 和先修环。邻接查询和 D3 力导向布局在浏览器内计算；D3 只决定坐标，不能决定知识关系。图谱 JSON 可导出，用于检查、备份或将来迁移。

添加节点时同步补齐：

1. 稳定 ID、实体类型、双语标题/摘要和完整正文。
2. 项目证据状态与个人学习状态的分离。
3. 能解释的先修/实现/记录/参考关系及其方向。
4. 可检查的代码或文档定位；外部来源注明固定版本。
5. 先修无环、所有关系端点存在、ID 不重复。

图谱数据改动后要编译前端并人工查看关系图和阅读内容。发布说明区分“代码已实现”“构建通过”“自动化验收”“真实模型/页面演示”；源码存在不能替代业务验收。

## Neo4j 属性图映射

如果需要迁移到 Neo4j，可把每条节点的 `kind` 映射为 `LearningNode` 标签并保留 `id`、`kind`、标题和属性；把 `type` 映射为有向关系类型，保留理由和来源信息。ID 建立唯一约束：

```cypher
CREATE CONSTRAINT learning_node_id IF NOT EXISTS
FOR (n:LearningNode) REQUIRE n.id IS UNIQUE;
```

概念性导入示例：

```cypher
UNWIND $nodes AS row
MERGE (n:LearningNode {id: row.id})
SET n.kind = row.kind,
    n.titleZh = row.titleZh,
    n.titleEn = row.titleEn,
    n.summaryZh = row.summaryZh,
    n.summaryEn = row.summaryEn;
```

实际导入应按关系类型建边，且要保证参数值来自已验证 catalogue。先修路径示意：

```cypher
MATCH path = (start:LearningNode {id: $lessonId})-[:PREREQUISITE*1..3]->(next)
RETURN path;
```

节点/边的 JSON 导出不代表已经建立数据库连接。切换到 Neo4j 需要单独决定认证、持久化、迁移、备份、授权和运行成本。

## 查询与图谱推荐

浏览器用 `id → outgoing edges / incoming edges` 邻接索引计算邻居和 1–2 跳邻域；课程搜索、阶段/实体/关系/掌握状态筛选后，再交给视图绘制。推荐规则只检查前置关系和当前本地掌握状态，向学习者解释推荐原因，不限制浏览其他知识。

个人笔记、阅读位置和掌握状态单独保存于浏览器 `localStorage`，以 schema version 管理，并支持进度 JSON 导出/导入。图谱目录中 `state` 描述工程实现进度，个人 mastery 描述学习者自评；任何代码提交都不能自动把学习者标为“已掌握”。进度没有云同步。

## 与 RAG / GraphRAG 的边界

当前 `/learning` 从图谱中搜索节点、查看关系、课程文章和源代码定位。这是用于导航和解释知识的学习图谱。它未向聊天 Agent 提供图查询，也未把图结构与文档向量检索组合，因此**不是 GraphRAG**。

若将来把企业工单知识接入图检索，应新增单独数据模型和授权/租户范围，对照向量检索评估答案依据、关系路径、引用定位、拒答能力、延迟和成本。不能把课程图中的先修关系当成企业业务事实。
