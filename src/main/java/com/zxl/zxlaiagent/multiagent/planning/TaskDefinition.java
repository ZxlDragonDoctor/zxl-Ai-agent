package com.zxl.zxlaiagent.multiagent.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 模型契约只包含声明字段，ID、状态和执行结果由服务端生成。 */
public record TaskDefinition(@JsonProperty("task_description") String description,
                             List<Integer> dependencies,
                             @JsonProperty("required_capability") String requiredCapability) {
    public TaskDefinition { dependencies = List.copyOf(dependencies); }
}
