package com.zxl.zxlaiagent.multiagent.planning;

import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.multiagent.model.Plan;
import com.zxl.zxlaiagent.multiagent.model.Task;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class PlanParser {
    private static final Set<String> FIELDS = Set.of("task_description", "dependencies", "required_capability");
    private static final Pattern FENCE = Pattern.compile("\\A```json\\h*\\R([\\s\\S]*)\\R```\\z", Pattern.CASE_INSENSITIVE);
    private final ObjectReader reader;
    private final PlanValidator validator;

    public PlanParser(ObjectMapper mapper, PlanValidator validator) {
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.validator = validator;
    }

    public Plan parse(String modelOutput, String originalGoal) {
        PlanValidator.require(modelOutput != null && !modelOutput.isBlank() && modelOutput.length() <= 64000,
                "INVALID_PLAN_JSON", "plan", "计划响应为空或超过 64000 字符");
        String json = modelOutput.strip();
        if (json.startsWith("```")) {
            var fence = FENCE.matcher(json);
            PlanValidator.require(fence.matches(), "INVALID_PLAN_JSON", "plan", "仅接受完整 JSON 数组或单个 json 代码围栏");
            json = fence.group(1).strip();
        }
        JsonNode root;
        try { root = reader.readTree(json); }
        catch (IOException e) { throw new PlanValidationException("INVALID_PLAN_JSON", "plan", "计划不是完整、唯一且有效的 JSON"); }
        PlanValidator.require(root != null && root.isArray(), "INVALID_PLAN_JSON", "tasks", "计划必须是 JSON 数组");
        PlanValidator.require(!root.isEmpty(), "EMPTY_PLAN", "tasks", "计划必须包含任务");
        PlanValidator.require(root.size() <= PlanValidator.MAX_TASKS, "TOO_MANY_TASKS", "tasks", "任务数量不能超过 20");
        List<TaskDefinition> definitions = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            String path = "tasks[" + i + "]";
            PlanValidator.require(node.isObject(), "INVALID_PLAN_FIELD", path, "每个任务必须是对象");
            node.fieldNames().forEachRemaining(name -> PlanValidator.require(FIELDS.contains(name),
                    "INVALID_PLAN_FIELD", path + "." + name, "任务包含未声明字段"));
            for (String field : FIELDS) PlanValidator.require(node.hasNonNull(field), "INVALID_PLAN_FIELD", path + "." + field, "任务缺少必需字段");
            JsonNode description = node.get("task_description");
            PlanValidator.require(description.isTextual() && !description.asText().isBlank() && description.asText().length() <= PlanValidator.MAX_DESCRIPTION,
                    "INVALID_PLAN_FIELD", path + ".task_description", "任务描述必须是 1–2000 字符的字符串");
            JsonNode capability = node.get("required_capability");
            PlanValidator.require(capability.isTextual(), "INVALID_PLAN_FIELD", path + ".required_capability", "能力必须是字符串");
            String normalized = PlanCapabilities.normalize(capability.asText(), path + ".required_capability");
            JsonNode deps = node.get("dependencies");
            PlanValidator.require(deps.isArray(), "INVALID_PLAN_FIELD", path + ".dependencies", "依赖必须是整数数组");
            List<Integer> indices = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (int j = 0; j < deps.size(); j++) {
                JsonNode dependency = deps.get(j);
                String depPath = path + ".dependencies[" + j + "]";
                PlanValidator.require(dependency.isIntegralNumber() && dependency.canConvertToInt(), "INVALID_PLAN_FIELD", depPath, "依赖索引必须是 JSON 整数");
                int index = dependency.intValue();
                PlanValidator.require(index >= 0 && index < root.size(), "UNKNOWN_DEPENDENCY", depPath, "依赖索引超出任务范围");
                PlanValidator.require(index != i, "SELF_DEPENDENCY", depPath, "任务不能依赖自身");
                PlanValidator.require(seen.add(index), "INVALID_PLAN_FIELD", depPath, "依赖索引不能重复");
                indices.add(index);
            }
            definitions.add(new TaskDefinition(description.asText().strip(), indices, normalized));
        }
        Plan plan = Plan.create(originalGoal);
        // 第一遍创建所有节点；第二遍转换引用，保留数组中的合法向后引用。
        for (TaskDefinition definition : definitions) {
            Task task = Task.create(definition.description());
            task.setInput(Map.of("capability", definition.requiredCapability()));
            plan.addTask(task);
        }
        for (int i = 0; i < definitions.size(); i++) {
            plan.getTasks().get(i).setDependencies(definitions.get(i).dependencies().stream()
                    .map(index -> plan.getTasks().get(index).getId()).toList());
        }
        validator.validate(plan);
        return plan;
    }
}
