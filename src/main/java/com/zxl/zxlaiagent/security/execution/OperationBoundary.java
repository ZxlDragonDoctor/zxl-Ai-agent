package com.zxl.zxlaiagent.security.execution;

import com.zxl.zxlaiagent.security.Phase;
import java.util.function.Supplier;

/** Explicit per-operation authorization, never a thread-local or model-controlled identity. */
public interface OperationBoundary {
    void check(Phase phase);

    /** Only sendAsync/subscribe/enqueue or a short local read/write belongs inside this callback. */
    <T> T start(Phase phase, Supplier<T> operation);

    /** Compatibility for isolated legacy demonstrations and offline algorithm tests only. */
    static OperationBoundary unrestricted() {
        return new OperationBoundary() {
            public void check(Phase phase) {}
            public <T> T start(Phase phase, Supplier<T> operation) { return operation.get(); }
        };
    }
}
