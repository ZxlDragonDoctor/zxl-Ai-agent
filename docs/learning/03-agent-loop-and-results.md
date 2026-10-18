# 第三课：执行预算、结束原因与工具结果契约

目标：把已经能调用工具的单 Agent，完善为有执行边界、能解释结束原因、能明确表达工具成败的执行器。

本课按一个完整功能单元推进，建议预留 90–120 分钟：先理解四个概念，连续完成四项改动，最后统一检查代码和页面行为。无需每完成一个字段就反馈，也不要求逐行断点。

## 当前进度与本批任务

你已在 `AiController` 读取 `agent.manus.max-steps`，并在每次创建 `ZxlManus` 后设置上限，Java 编译通过。检查时发现本地 YAML 原先写成 `spring.ai.maxus.max-steps`，已调整为顶层的 `agent.manus.max-steps: 20`。属性名中的默认值 `:20` 会在配置缺失时生效，因此原来的路径错误被相同的默认值掩盖。

2026-10-16 已在页面演示启动参数临时覆盖上限为 1：时间查询的工具已返回，整体达到步数上限，没有下一轮最终回答；直接回答 `1+1` 则在一步内正常结束。两种行为的 [页面对比](../images/lesson3-run-outcomes.jpg) 和 [工具实际返回](../images/lesson3-step-limit.jpg) 已保存，演示结束后恢复本地上限 20。

2026-10-18 已完成四项工程改动并集中验收：48 个本地用例通过，前端构建通过，真实模型完成正常时间查询、无效时区解释和工具终止。详见 [第三课验收记录](03-acceptance.md)。学习进度以你能解释和独立改动为准，下一课进入 [会话与记忆](04-conversations-and-memory.md)。

| 任务 | 要实现的内容 | 解决的问题 |
| --- | --- | --- |
| 1，已完善 | 校验执行步数，统一同步与流式的预算判断 | 无效预算、最后一步完成被误判为超限 |
| 2，已实现 | 明确保存结束原因，并通过 SSE 发送 | 只知道结束了，无法解释为什么 |
| 3，已实现 | 所有本地工具返回统一结构，MCP 适配保留错误标记 | 业务逻辑依赖自然语言前缀，错误信息难以稳定使用 |
| 4，已实现 | 页面显示结束原因和结构化结果，保留已有流式输出 | 用户无法区分工具成败与整体执行结果 |

## 1. 四个概念一起理解

### 1.1 一步是什么

当前 `step()` 包含一次模型请求，以及本轮模型请求的工具执行。模型不请求工具时，这一步保存回答并结束。工具执行后，下一次模型请求属于下一步。

因此，“查询时间并给出回答”通常需要两步；同一轮模型可以请求多个工具，这些工具仍属于同一步。步数、工具次数和输出分片数量需要分别理解。

### 1.2 为什么要限制步数

循环可能一直选择工具、重复查询或无法满足任务目标。步数上限控制模型决策轮次；单次调用耗时、Token 费用和工具调用数仍需要各自的预算，后续课程再扩展。

### 1.3 状态与结束原因

状态描述任务现在处于哪个阶段，结束原因解释它为什么停止。例如两次执行都停止了，一次因为模型生成了回答，另一次因为预算用完。调用了终止工具也不能自动证明用户目标已经实现。

### 1.4 工具结果与任务结果

工具返回 `success=false`，模型仍可能解释错误、请求澄清或修正参数。工具抛出未处理的执行异常，则可能导致整个任务失败。模型最终结束回答，也不自动证明答案或业务操作正确；答案质量和业务后置条件在后续评估中检查。

## 2. 代码阅读地图

只需围绕这些位置改动，无需从头遍历仓库：

| 文件 | 本课职责 |
| --- | --- |
| [AiController](../../src/main/java/com/zxl/zxlaiagent/controller/AiController.java) | 读取配置，为每次请求创建 Agent 并设置预算 |
| [BaseAgent](../../src/main/java/com/zxl/zxlaiagent/agent/BaseAgent.java) | `run()`、`runStream()`、步数与结束事件 |
| [ReActAgent](../../src/main/java/com/zxl/zxlaiagent/agent/ReActAgent.java) | `think()` 无工具时保存最终回答并结束 |
| [ToolCallAgent](../../src/main/java/com/zxl/zxlaiagent/agent/ToolCallAgent.java) | 区分模型错误、工具异常、终止工具，回填结果 |
| [AgentExecutionEvent](../../src/main/java/com/zxl/zxlaiagent/agent/model/AgentExecutionEvent.java) | SSE 事件字段与工厂方法 |
| [CurrentTimeTool](../../src/main/java/com/zxl/zxlaiagent/tools/CurrentTimeTool.java) | 将工具返回值改为结构化结果 |
| [AgentChat](../../zxl-Ai-agent-fronted/src/views/AgentChat.vue)、[AgentExecutionFlow](../../zxl-Ai-agent-fronted/src/components/AgentExecutionFlow.vue) | 第四课统一了页面；仍保存并展示事件、参数、结果和终态 |

## 3. 任务一：补齐执行预算的边界

### 要求

1. 保留已完成的配置读取，默认上限为 20。
2. 在 `BaseAgent` 显式编写 `setMaxSteps(int maxSteps)`，仅接受 1–50。这个范围是本课的演示策略，可按业务调整。
3. Agent 已开始运行后拒绝修改预算，避免运行中的任务获得额外轮次。
4. `run()` 和 `runStream()` 使用相同的超限判断规则。

当前实现：

```java
public synchronized void setMaxSteps(int maxSteps) {
    if (maxSteps < 1 || maxSteps > 50) {
        throw new IllegalArgumentException("maxSteps 必须在 1 到 50 之间");
    }
    if (state != AgentState.IDLE) {
        throw new IllegalStateException("只能在 IDLE 状态设置 maxSteps，当前状态: " + state);
    }
    this.maxSteps = maxSteps;
}
```

`@Data` 遇到已有同名 setter 时不会再生成该 setter，无需移除整个类的 Lombok 注解。

关键判断参考现有流式实现：

```java
boolean reachedLimit = state == AgentState.RUNNING
        && currentStep >= maxSteps;
```

为什么要检查 `RUNNING`？如果模型恰好在最后允许的一步生成了最终回答，`step()` 已把状态设为 `FINISHED`，这次应该按正常结束处理。

同步 `run()` 和流式 `runStream()` 现已共用 `hasReachedStepLimit()`，并且都只在 `RUNNING` 时继续执行。步骤进入 `ERROR` 后，两条路径都按失败处理，避免继续执行或发出成功终态。

非法配置应在请求模型前被拒绝，不能出现“0 步、已完成”。本任务先保证 setter 的约束；启动时的统一配置校验可以复用同一规则。

### 本次修改要理解的地方

| 原实现 | 问题 | 修正原则 |
| --- | --- | --- |
| `maxSteps < 1 && maxSteps > 50` | 一个数不可能同时小于 1 且大于 50 | 越过任意一侧都无效，用 `||` |
| `state == IDLE` 时抛异常 | 拒绝了允许设置的状态 | 非 `IDLE` 才拒绝 |
| 抛 `IllegalAccessException` | 把参数范围问题表达成了访问权限问题，迫使调用链声明检查型异常 | 使用 `IllegalArgumentException`；移除构造器和 Controller 传播的 `throws` |
| 拒绝设置时把状态改成 `ERROR` | 错误配置请求破坏了现有 Agent 状态 | 校验失败不修改状态或原预算 |

预算 setter 和开始运行的 `startRun()` 使用同一把短锁，使“检查空闲状态 → 修改预算”和“检查空闲状态 → 开始运行”互斥。锁仅覆盖初始化，不覆盖模型和工具执行。`state` 使用 `volatile`，让执行线程与 SSE 回调看到最新状态；多字段操作的互斥仍依靠锁。

### 本地边界检查

[BaseAgentBudgetTest](../../src/test/java/com/zxl/zxlaiagent/agent/BaseAgentBudgetTest.java) 用本地步骤模拟正常完成、继续执行和错误，不加载 Spring 容器，也不调用模型。执行方式：

```text
mvn -q -Dtest=BaseAgentBudgetTest test
```

本次结果：21 个用例，失败 0，错误 0。这里只验证预算与循环控制；任务二至四和真实模型链路的完整验收分别记录。

## 4. 任务二：保存明确的结束原因

新增 `AgentEndReason` 枚举，建议放在 `agent/model`。沿用现有 `AgentState`，增加一个独立的结束原因字段。

| 结束原因 | 发生位置 | 对外展示 |
| --- | --- | --- |
| `ANSWER_READY` | `ReActAgent.step()` 中 `think()` 返回 false，已保存模型回答 | 已完成 |
| `STEP_LIMIT` | 仍在运行，但没有剩余步数 | 已达到步数上限 |
| `TERMINATED_BY_TOOL` | 实际执行了 `doTerminate` | 已终止 |
| `MODEL_ERROR` | 请求模型或解析模型响应失败 | 模型调用失败 |
| `TOOL_ERROR` | 工具执行阶段抛出未处理异常 | 工具执行失败 |
| `EXECUTION_ERROR` | 其他未分类的执行异常 | 执行失败 |
| `TOOL_RETURNED` | 带 returnDirect 的工具成功，直接提供真实结果 | 工具已直接返回结果 |
| `OUTPUT_INTERRUPTED` | 输出连接中断或停止接收 | 已停止接收 |

### 改动要求

- 每次设置结束原因，都对应真实发生的代码分支。
- 外层 `catch` 已有更具体原因时不要覆盖为 `EXECUTION_ERROR`。
- `STEP_LIMIT` 与正常回答分开判断；终止工具有独立原因。
- 给 `AgentExecutionEvent` 增加可为空的 `endReason`，终态事件填写枚举名，增量事件可为空。
- 同时更新 record 的工厂方法及所有构造位置，避免漏改。
- 保留现有 SSE 事件类型和兼容文本事件，降低对现有页面的影响。

推荐的终态映射：

| 原因 | SSE 类型 | status |
| --- | --- | --- |
| `ANSWER_READY` | `RUN_COMPLETED` | `completed` |
| `STEP_LIMIT` | `RUN_COMPLETED` | `limit` |
| `TERMINATED_BY_TOOL` | `RUN_COMPLETED` | `terminated` |
| 三类执行异常 | `RUN_FAILED` | `error` |

这里保留 `RUN_COMPLETED` 作为兼容的结束事件类型，其业务含义由 `status` 和 `endReason` 共同说明。未来事件协议统一时可以再调整命名。

本课先覆盖正常运行、预算和异常分支。页面“停止接收”仍只表示停止接收输出；完整的底层取消、超时协调和并发状态控制在第七课处理。

## 5. 任务三：所有工具共用结果契约

时间、文件读写、网页搜索、抓取、下载、终端、PDF、图片与终止共 9 类本地工具、10 个方法已统一使用 `ToolResult<T>`。MCP 工具通过协议适配转换成同一外层契约，保留原始数据与 `isError`，业务判断读取明确字段。

建议新增两个 Java record：

```java
public record ToolResult<T>(
        boolean success,
        String code,
        String message,
        T data
) {}

public record CurrentTimeData(String zoneId, String currentTime) {}
```

将 `getCurrentTime` 返回类型改为 `ToolResult<CurrentTimeData>`，保留原来的 `@Tool` 和参数校验。

约定：

| 情况 | success | code | data |
| --- | --- | --- | --- |
| 查询成功 | true | `OK` | 实际时区与时间 |
| 参数为空 | false | `MISSING_ZONE` | null |
| 无效时区 | false | `INVALID_ZONE` | null |

成功返回示意，时间只作格式示例：

```json
{
  "success": true,
  "code": "OK",
  "message": "时间查询成功",
  "data": {
    "zoneId": "Asia/Shanghai",
    "currentTime": "2026-10-16T09:00:00+08:00"
  }
}
```

### 注意三个连接点

1. 注册方式继续使用 `ToolCallbacks.from(...)`，不要绕过实际工具管理器另写一套调用。
2. 模型上下文中要回填完整结果，包括 `success=false` 的错误结果，不能只发送前端卡片。
3. `ToolResults.read()` 严格读取契约，不再通过自然语言前缀猜测成败。MCP 使用协议错误标记；页面读取结构化字段。超过展示长度时裁剪 data 后重新序列化，不能破坏 JSON 外层。

先让 Java 返回 record，由现有工具结果转换器处理。不要手动拼 JSON，也不要仅为了让卡片显示而改写底层业务结果。

本任务的 JSON 来自应用代码。模型生成的 JSON 还需要结构校验与业务校验，不能因为同样是 JSON 就视作可信业务结果。按工程依赖，第五课会在模型生成 Plan 时继续实现这部分校验。

## 6. 任务四：把契约落实到页面

在 TypeScript 的 `AgentStreamEvent` 与 `AgentFlow` 增加可选 `endReason`。收到终态事件时保存它，运行中不要提前写成功原因。

页面要求：

- `ANSWER_READY`、`STEP_LIMIT`、`TERMINATED_BY_TOOL` 能显示不同的结束提示。
- 若使用新的 `terminated` 状态，同时更新 `RunStatus`、`finishReply()` 映射和中英文文案，避免落回 `completed`。
- 模型错误与工具执行异常显示各自原因，同时保留已经收到的工具记录和部分回答。
- `ToolResult.success=false` 的工具卡片显示错误；模型随后解释参数错误时，整个任务可以正常结束。
- 继续展示实际模型思考与回答分片，结束事件到达后关闭 SSE，避免自动重连重复提交。
- “参数与结果”继续可以展开，结构化结果不能丢失 `code`、`message` 或 `data`。

可复用当前页面的工具结果格式化与卡片结构，本任务主要是接通新的字段和结束状态。

## 7. 一次集中验收

先连续写完四项改动，再统一编译与查看页面，无需每一项都重启后端。集中验收时按下面场景核对，所需配置调整可以通过本地启动参数临时覆盖，结束后恢复正常配置。

| 场景 | 预期 |
| --- | --- |
| 上限 1，模型直接回答 `1+1`，无工具调用 | 一步正常结束，`ANSWER_READY`，不能标为超限 |
| 上限 1，模型实际请求时间工具 | 工具已返回，整体 `STEP_LIMIT`，没有下一轮回答 |
| 上限 20，查询时间并说明 | 工具结果回填，模型随后回答，通常两步结束 |
| 无效时区实际进入时间工具 | 卡片为错误，结果包含 `success:false`、`INVALID_ZONE`，模型能解释或澄清 |
| 上限设为 0 或 51 | 在模型请求之前拒绝，不能显示成功 |
| 恰好在最后允许的一步正常回答 | 同步与流式均不能误判为 `STEP_LIMIT` |

无效时区可能被模型直接识别并要求澄清，此时按实际页面记录说明“未调用工具”，不将它算作工具错误场景已经验收。模型服务中断和工具执行异常的分支需保留明确处理；可控故障演练在后续有对应工具或替身时统一补充。

本课四项工程任务已实现，已运行的场景和外部依赖边界记录在 [验收报告](03-acceptance.md)。前端由助手完成，你可以集中阅读 Java 执行链路并完成面试复盘。

## 8. 面试复盘与统一反馈

完成后，尝试回答：

1. Agent 中的 maxSteps 控制什么？为什么不能直接当作费用上限？
2. 工具成功、模型生成回答、业务目标完成分别怎样判断？
3. 为什么达到上限的判断必须结合当前状态？
4. 业务参数错误为什么可以作为工具结果返回，哪些异常应终止任务？
5. 为什么有了枚举状态，还要保存结束原因？
6. JSON 格式正确，为什么不代表返回内容或业务结果正确？

全部完成后一次反馈即可：

```text
第三课本批次已完成。
已实现：预算校验 / 结束原因 / 结构化工具结果 / 页面展示
已观察的场景：
与预期不同的地方：
尚未理解的设计：
```

我会集中检查职责、边界条件和页面行为，统一反馈问题，再进入下一批工程任务。
