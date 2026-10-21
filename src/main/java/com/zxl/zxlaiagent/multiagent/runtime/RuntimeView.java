package com.zxl.zxlaiagent.multiagent.runtime;

public record RuntimeView(long deadlineAt, boolean cancelRequested, int activeTasks,
                          int activeModelCalls, boolean cleanupPending, String waitingFor) {}
