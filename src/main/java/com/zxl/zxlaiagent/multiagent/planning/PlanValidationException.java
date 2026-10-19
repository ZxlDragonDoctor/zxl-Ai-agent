package com.zxl.zxlaiagent.multiagent.planning;

import com.zxl.zxlaiagent.multiagent.model.WorkflowException;

public class PlanValidationException extends WorkflowException {
    public PlanValidationException(String code, String path, String message) { super(code, path, message); }
}
