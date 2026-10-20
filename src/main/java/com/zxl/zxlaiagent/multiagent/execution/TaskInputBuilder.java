package com.zxl.zxlaiagent.multiagent.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.model.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TaskInputBuilder {
    private final ObjectMapper mapper;
    public TaskInputBuilder(ObjectMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> snapshotUserInput(Map<String, Object> input) {
        try {
            String json = mapper.writeValueAsString(input == null ? Map.of() : input);
            if (json.length() > 32000) throw new WorkflowException("CONTEXT_TOO_LARGE", "context", "业务输入超过 32000 字符");
            Map<String, Object> copy = mapper.readValue(json, new TypeReference<>() {});
            return freezeMap(copy);
        } catch (WorkflowException e) { throw e; }
        catch (Exception e) { throw new WorkflowException("INVALID_CONTEXT", "context", "业务输入必须可以序列化为 JSON", e); }
    }
    static Map<String, Object> freezeMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(key.toString(), freeze(value)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) return freezeMap(map);
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(TaskInputBuilder::freeze).toList());
        return value;
    }
    public TaskExecutionContext build(Plan plan, Task recipient, Map<String, Object> userInput) {
        Map<String, Task> tasks = new HashMap<>();
        plan.getTasks().forEach(task -> tasks.put(task.getId(), task));
        List<TaskExecutionContext.DependencyResult> results = new ArrayList<>();
        int characters = 0;
        for (String id : recipient.getDependencies()) {
            Task source = tasks.get(id);
            TaskSnapshot state = source == null ? null : source.snapshot();
            if (state == null || state.status() != TaskStatus.COMPLETED)
                throw new WorkflowException("DEPENDENCY_NOT_COMPLETED", "tasks." + recipient.getId() + ".dependencies", "依赖任务尚未成功完成：" + id);
            if (state.result() == null || state.result().isBlank() || state.assignedAgentId() == null)
                throw new WorkflowException("DEPENDENCY_OUTPUT_MISSING", "tasks." + recipient.getId() + ".dependencies", "依赖任务缺少真实输出或执行者：" + id);
            characters += state.result().length();
            if (characters > 100000) throw new WorkflowException("DEPENDENCY_OUTPUT_TOO_LARGE", "tasks." + recipient.getId(), "依赖输出超过上下文预算，请先设计摘要节点");
            results.add(new TaskExecutionContext.DependencyResult(id, source.getDescription(), state.assignedAgentId(), state.result()));
        }
        return new TaskExecutionContext(plan.getGoal(), userInput, results);
    }
}
