package com.zxl.zxlaiagent.usage;

import com.zxl.zxlaiagent.security.*;
import java.util.Objects;
import java.util.UUID;

/** Deployment policy only. Actor and run are captured from trusted Java execution scope. */
public final class ScopedModelBudgetFactory {
    public record Policy(boolean enabled, long tokens, int calls, long estimate) {
        public Policy {
            if (tokens < 1 || calls < 1 || estimate < 1 || estimate > tokens)
                throw new IllegalArgumentException("Valid bounded usage policy required");
        }
    }
    private final SecurityDatabase database;
    private final Policy policy;
    private final BoundedModelRouter router;
    public ScopedModelBudgetFactory(SecurityDatabase database, Policy policy) {
        this(database, policy, null);
    }
    public ScopedModelBudgetFactory(SecurityDatabase database, Policy policy, BoundedModelRouter router) {
        this.database = Objects.requireNonNull(database);
        this.policy = Objects.requireNonNull(policy);
        this.router = router;
    }
    public boolean enabled() { return policy.enabled(); }
    public BoundedModelRouter router() { return policy.enabled() ? router : null; }
    public void initialize(ActorRef actor, String runId) {
        if (!policy.enabled()) return;
        Objects.requireNonNull(actor, "Trusted actor required");
        if (runId == null) throw new IllegalArgumentException("Trusted run required");
        UUID.fromString(runId);
        database.tx(connection -> {
            try (var statement = SecurityDatabase.statement(connection,
                    "INSERT INTO token_budget_account(tenant_id,account_id,token_limit,call_limit) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                    actor.tenantId(), runId, policy.tokens(), policy.calls())) {
                statement.executeUpdate();
            }
            try (var statement = SecurityDatabase.statement(connection,
                    "SELECT token_limit,call_limit FROM token_budget_account WHERE tenant_id=? AND account_id=?",
                    actor.tenantId(), runId); var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != policy.tokens() || rows.getInt(2) != policy.calls())
                    throw new IllegalStateException("BUDGET_POLICY_MISMATCH");
            }
            return null;
        });
    }
    public ModelCallBudget call(ActorRef actor, String runId) {
        if (!policy.enabled()) return null;
        initialize(actor, runId);
        return new TokenCallBudget(new TokenBudgetLedger(database, actor.tenantId()), runId,
                UUID.randomUUID().toString(), policy.estimate());
    }
}
