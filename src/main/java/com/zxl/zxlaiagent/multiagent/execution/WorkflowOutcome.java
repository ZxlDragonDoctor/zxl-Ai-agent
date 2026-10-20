package com.zxl.zxlaiagent.multiagent.execution;

import com.zxl.zxlaiagent.multiagent.model.WorkflowError;
import java.util.List;

/** HTTP、终态 SSE 和调用者共同使用的不可变业务结果。 */
public record WorkflowOutcome(String executionId, String planId, String goal, String status,
                              long startedAt, long finishedAt, List<TaskExecutionView> tasks,
                              TaskExecutionView planningTask, TaskExecutionView synthesisTask,
                              int planningModelCalls, int workerModelCalls, int synthesisModelCalls,
                              String result, WorkflowError error) {
    public WorkflowOutcome { tasks = List.copyOf(tasks); }
    public boolean successful() { return "completed".equals(status); }
}
