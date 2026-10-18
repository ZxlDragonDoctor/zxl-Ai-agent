package com.zxl.zxlaiagent.conversation.model;

import java.util.Objects;

public record ToolCallRecord(String id, String name, String arguments) {
    public ToolCallRecord {
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具调用 ID 与名称不能为空");
        }
        Objects.requireNonNull(arguments, "工具参数不能为空");
    }
}
