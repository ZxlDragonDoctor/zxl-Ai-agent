package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.security.ActorRef;
import java.util.Objects;

/** 服务端创建的运行身份与私有事件出口；不得从模型输入反序列化或推导。发布者仍须执行实时授权。 */
public record TrustedRunContext(ActorRef actor, String runId, PrivateRunEvents events,
                                WorkflowRun.ReportTransaction reportTransaction) {
    public TrustedRunContext(ActorRef actor, String runId, PrivateRunEvents events) {
        this(actor, runId, events, null);
    }
    public TrustedRunContext {
        Objects.requireNonNull(actor,"Trusted actor required");
        Objects.requireNonNull(runId,"Run id required");
        Objects.requireNonNull(events,"Private event sink required");
        if(runId.isBlank())throw new IllegalArgumentException("Run id must not be blank");
    }
}
