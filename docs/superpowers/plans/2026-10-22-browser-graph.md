# Neo4j Browser 风格图谱实施计划

**Goal:** 按用户确认的经典 Browser 参照改造学习图谱。

**Architecture:** 将邻域探索与几何计算放入纯函数模块；Canvas 管理 D3 物理布局和相机，LearningGraph 管理选择、筛选与探索根节点。

**Tech Stack:** Vue 3、TypeScript、D3 force、SVG、Node 内置测试。

- [ ] 新增 graphPresentation.ts，提供沿节点半径截断的边几何、文本换行、可共享邻居的探索集合计算；验证退化距离与展开/收起。
- [ ] 重写 KnowledgeGraphCanvas.vue：白底节点与线、逐帧刷新、拖拽/固定、环形菜单、以鼠标为中心缩放、适应视图和全屏。
- [ ] 修改 LearningGraph.vue，分离选中节点与探索根节点，连接展开、隐藏、重置事件并扩大画布。
- [ ] 运行纯函数检查及 npm run build，记录浏览器视觉验收限制，更新图谱维护指南。
- [ ] 按数据几何、画布界面、文档分别选择性提交并 push，确认远程同步。
