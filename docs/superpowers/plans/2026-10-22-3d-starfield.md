# 3D 星空图谱实施计划

**Goal:** 将用户确认的 3D 星空网络设为默认学习图谱，支持持续动态、旋转和聚焦。

**Architecture:** 3dGraphModel.ts 负责不可变数据适配和相机坐标；KnowledgeGraph3D.vue 管理 Three.js 场景、交互与资源；LearningGraph.vue 按需加载并提供 2D 回退。

**Tech Stack:** Vue 3、3d-force-graph、Three.js、Node 内置测试。

- [ ] 锁定 3D 依赖并检查类型与生命周期 API。
- [ ] 实现节点副本、连接数和相机聚焦纯函数，检查不变性与退化坐标。
- [ ] 实现发光节点、真实关系流动粒子、星空、自动环绕、悬停高亮、聚焦、暂停和资源释放。
- [ ] 默认 3D 全图，接入阅读、筛选、2D 切换、错误回退和减少动态模式。
- [ ] 构建并记录证据，将 3D 图谱构建原理加入知识库，分职责提交及 push。
