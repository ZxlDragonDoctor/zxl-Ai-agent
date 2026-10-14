# 多 Agent 编排失败生命周期设计

日期：2026-10-14

## 背景

项目基于 Spring Boot 和 Spring AI，已有 Planner、Code、Research、Supervisor Agent，以及负责生成计划和分派任务的 `OrchestratorAgent`。计划由有依赖关系的 `Task` 组成，Agent 通过 `CompletableFuture` 异步执行任务。

当前实现存在两个相互影响的问题：

1. 单个任务失败后，`executePlan` 可能因没有可运行任务而退出，但 `orchestrate` 仍继续综合结果并把计划标为 `COMPLETED`。
2. `AbstractBaseAgent` 遇到一次任务异常后把 Agent 留在 `ERROR`。编排器只选择 `IDLE` Agent，因此该 Agent 后续不能再接任务。

另外，规划器可能返回空任务列表。空计划当前会被视为已完成，也需要纳入正确性规则。

## 目标

- 计划只有在至少包含一个任务且所有任务均完成时才能进入 `COMPLETED`。
- 任一任务失败、或计划中仍有任务但依赖关系无法继续满足时，计划进入 `FAILED`，并向调用方提供可读原因。
- 依赖失败或无法满足的待执行任务进入 `BLOCKED`，保留其阻塞原因。
- 单次任务异常只让该任务失败；处理完成后 Agent 回到 `IDLE`，可以继续接收后续任务。
- 保持现有 HTTP 和 SSE 响应结构，不引入新的 API。
- 实施时按“概念说明 → 调用链 → 小步改动 → 复盘”的节奏讲解 Agent 状态机和任务依赖。

## 方案

### 任务与计划状态

一次计划执行按以下规则处理：

1. 编排器按依赖关系取得当前可运行的任务，并并行执行这一批任务。
2. 一批任务结束后，继续执行仍然可运行的独立任务。只有依赖任务已完成，后续任务才可运行。
3. 如果某任务失败，其依赖任务不能继续执行；将这些仍处于 `PENDING` 的任务标记为 `BLOCKED`，同时保存阻塞原因。
4. 如果计划还有待执行任务但没有任何可运行任务，将剩余任务标记为 `BLOCKED`。这覆盖缺失依赖和循环依赖造成的停滞。
5. 计划任务列表为空时，计划失败并给出“没有可执行任务”的原因。
6. 存在 `FAILED` 或 `BLOCKED` 任务时，计划进入 `FAILED`，发布 `PLAN_FAILED`，并让调用方收到失败信息。不得把部分成功结果误报为完整成功。
7. 只有所有任务都为 `COMPLETED` 时，才综合结果并发布 `PLAN_COMPLETED`。

不新增状态枚举。使用现有的 `TaskStatus.BLOCKED`、`PlanStatus.FAILED` 和 `EventType.PLAN_FAILED`。

### Agent 生命周期

`AbstractBaseAgent` 成功处理任务后沿用现有流程：`IDLE → BUSY → IDLE`，任务进入 `COMPLETED` 并发布完成事件。

处理任务时发生异常则按以下顺序收尾：记录任务错误、把任务标记为 `FAILED`、发布 `TASK_FAILED`，再将 Agent 从 `BUSY` 恢复为 `IDLE`，并继续把原错误交给编排器处理。Agent 的可用状态与某一次任务是否成功分开表示：错误保存在任务上，Agent 不因一次失败永久失去调度资格。

### 调用方行为

`MultiAgentController` 继续使用现有结果字段：成功时返回 `success` 和 `result`；失败时返回 `success: false` 和 `error`。SSE 继续通过现有 `error` 事件返回原因。此阶段不改变接口格式，也不增加部分结果协议。

## 范围外

- 自动重试、补偿执行和人工恢复策略。
- Agent 级健康检查、永久性故障分类和熔断。
- 计划取消、超时策略重构及并发容量限制。
- 前端进度动画、事件流和指标修正。
- 规划器提示词、模型供应商或任务分解格式改造。

## 验收场景

- 单任务成功：任务和计划均为 `COMPLETED`，调用方收到成功结果。
- 单任务失败：任务为 `FAILED`，计划为 `FAILED`，调用方看到错误原因；同一 Agent 可以随后接收新任务。
- 有依赖的任务链中前置任务失败：后续任务为 `BLOCKED`，计划为 `FAILED`。
- 计划中既有失败任务又有独立可执行任务：独立任务仍可执行，最终计划仍为 `FAILED`。
- 计划包含循环或无效依赖：执行停止后剩余任务为 `BLOCKED`，计划为 `FAILED`，原因可读。
- 空计划：计划失败，不得返回成功。
- 全部任务成功：所有任务完成后才发布 `PLAN_COMPLETED`；若 Supervisor 综合失败，沿用现有的原始结果回退行为。

## 预期涉及范围

主要修改 `multiagent/orchestration/OrchestratorAgent.java`、`multiagent/agents/AbstractBaseAgent.java` 和 `multiagent/model/Plan.java`；如现有响应无法显示具体失败原因，再检查 `controller/MultiAgentController.java`。保留工作区中已有的其他修改。
