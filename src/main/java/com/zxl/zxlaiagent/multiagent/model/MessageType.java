package com.zxl.zxlaiagent.multiagent.model;

public enum MessageType {
    TASK_ASSIGN,
    TASK_RESULT,
    TASK_PROGRESS,
    TASK_ERROR,
    QUERY,
    RESPONSE,
    STATUS_UPDATE,
    HEARTBEAT,
    SHUTDOWN,
    PLAN_UPDATE,
    DELEGATE,
    COORDINATE
}
