package com.zxl.zxlaiagent.multiagent.planning;

import com.zxl.zxlaiagent.multiagent.model.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** 只校验入口计划，使用临时入度表，不通过修改 Task 状态遍历图。 */
@Component
public class PlanValidator {
    public static final int MAX_TASKS = 20;
    public static final int MAX_DESCRIPTION = 2000;

    public void validate(Plan plan) { validate(plan, PlanCapabilities.SUPPORTED); }

    public void validate(Plan plan, Set<String> registeredTypes) {
        require(plan != null, "INVALID_PLAN_FIELD", "plan", "计划不能为空");
        require(plan.getId() != null && !plan.getId().isBlank(), "INVALID_PLAN_FIELD", "id", "计划 ID 不能为空");
        require(plan.getGoal() != null && !plan.getGoal().isBlank() && plan.getGoal().length() <= 12000,
                "INVALID_PLAN_FIELD", "goal", "原始目标不能为空且不能超过 12000 字符");
        require(plan.getStatus() == PlanStatus.CREATED, "INVALID_PLAN_FIELD", "status", "入口计划必须处于 CREATED 状态");
        var tasks = plan.getTasks();
        require(tasks != null && !tasks.isEmpty(), "EMPTY_PLAN", "tasks", "计划必须包含任务");
        require(tasks.size() <= MAX_TASKS, "TOO_MANY_TASKS", "tasks", "任务数量不能超过 20");
        Map<String, Task> byId = new LinkedHashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            String path = "tasks[" + i + "]";
            require(task != null, "INVALID_PLAN_FIELD", path, "任务不能为空");
            require(task.getId() != null && !task.getId().isBlank(), "INVALID_PLAN_FIELD", path + ".id", "任务 ID 不能为空");
            require(byId.putIfAbsent(task.getId(), task) == null, "DUPLICATE_TASK_ID", path + ".id", "任务 ID 不能重复");
            require(task.getDescription() != null && !task.getDescription().isBlank() && task.getDescription().length() <= MAX_DESCRIPTION,
                    "INVALID_PLAN_FIELD", path + ".description", "任务描述不能为空且不能超过 2000 字符");
            require(TaskSnapshot.pending().equals(task.snapshot()), "INVALID_PLAN_FIELD", path + ".execution",
                    "入口任务不能携带执行状态、调用统计或伪造结果");
            require(task.getInput() != null && task.getInput().size() == 1 && task.getInput().get("capability") instanceof String,
                    "INVALID_PLAN_FIELD", path + ".input", "计划输入只能声明 capability");
            String capability = PlanCapabilities.normalize((String) task.getInput().get("capability"), path + ".capability");
            require(registeredTypes.contains(capability), "AGENT_TYPE_NOT_REGISTERED", path + ".capability", "计划所需的工作 Agent 类型尚未注册");
            require(task.getDependencies() != null, "INVALID_PLAN_FIELD", path + ".dependencies", "依赖列表不能为空");
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> downstream = new HashMap<>();
        for (Task task : tasks) { indegree.put(task.getId(), 0); downstream.put(task.getId(), new ArrayList<>()); }
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            Set<String> seen = new HashSet<>();
            for (int j = 0; j < task.getDependencies().size(); j++) {
                String dependency = task.getDependencies().get(j);
                String path = "tasks[" + i + "].dependencies[" + j + "]";
                require(dependency != null && byId.containsKey(dependency), "UNKNOWN_DEPENDENCY", path, "依赖的任务不存在");
                require(!dependency.equals(task.getId()), "SELF_DEPENDENCY", path, "任务不能依赖自身");
                require(seen.add(dependency), "INVALID_PLAN_FIELD", path, "依赖不能重复");
                downstream.get(dependency).add(task.getId());
                indegree.merge(task.getId(), 1, Integer::sum);
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Task task : tasks) if (indegree.get(task.getId()) == 0) ready.add(task.getId());
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (String next : downstream.get(id)) if (indegree.merge(next, -1, Integer::sum) == 0) ready.addLast(next);
        }
        require(visited == tasks.size(), "PLAN_CYCLE", "tasks", "任务计划包含循环依赖");
    }

    static void require(boolean valid, String code, String path, String message) {
        if (!valid) throw new PlanValidationException(code, path, message);
    }
}
