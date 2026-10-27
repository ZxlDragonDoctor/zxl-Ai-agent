package com.zxl.zxlaiagent.security.execution;

import com.zxl.zxlaiagent.security.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Captures trusted identity and Java-selected permissions; checks current authority at every phase. */
public final class ActorBoundary implements OperationBoundary {
    private final AuthorizationService authorization;
    private final ActorRef actor;
    private final List<AuthorizationService.Permission> permissions;
    private final String runId;

    public ActorBoundary(AuthorizationService authorization, ActorRef actor,
                         List<AuthorizationService.Permission> permissions, String runId) {
        this.authorization = Objects.requireNonNull(authorization);
        this.actor = Objects.requireNonNull(actor);
        this.permissions = List.copyOf(permissions);
        if (this.permissions.isEmpty() || this.permissions.size() > 8) throw new IllegalArgumentException("Bounded permissions required");
        this.runId = runId;
    }

    @Override public void check(Phase phase) { start(phase, () -> null); }

    @Override public <T> T start(Phase phase, Supplier<T> operation) {
        return authorization.gateAll(actor, permissions, phase, runId, operation);
    }
}
