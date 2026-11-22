package com.zxl.zxlaiagent.usage;

import com.zxl.zxlaiagent.security.*;
import java.util.Objects;
import java.util.UUID;
import java.time.*;

/** Deployment policy only. Actor and run are captured from trusted Java execution scope. */
public final class ScopedModelBudgetFactory {
    public record Policy(boolean enabled, long tokens, int calls, long estimate, long periodTokens, int periodCalls) {
        public Policy(boolean enabled,long tokens,int calls,long estimate) { this(enabled,tokens,calls,estimate,1000000L,1000); }
        public Policy {
            if (tokens < 1 || calls < 1 || estimate < 1 || estimate > tokens || periodTokens < estimate || periodCalls < 1)
                throw new IllegalArgumentException("Valid bounded usage policy required");
        }
    }
    private final SecurityDatabase database;
    private final Policy policy;
    private final BoundedModelRouter router;
    private final Clock clock;
    public ScopedModelBudgetFactory(SecurityDatabase database, Policy policy) {
        this(database, policy, null);
    }
    public ScopedModelBudgetFactory(SecurityDatabase database, Policy policy, BoundedModelRouter router) {
        this(database,policy,router,Clock.systemUTC());
    }
    public ScopedModelBudgetFactory(SecurityDatabase database, Policy policy, BoundedModelRouter router, Clock clock) {
        this.clock=Objects.requireNonNull(clock);
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
            String period=LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
            try(var statement=SecurityDatabase.statement(connection,"SELECT period_id,accepted_calls FROM token_budget_account WHERE tenant_id=? AND account_id=? FOR UPDATE",actor.tenantId(),runId);var rows=statement.executeQuery()) {
                rows.next(); if(rows.getString(1)==null && rows.getInt(2)>0) throw new IllegalStateException("LEGACY_BUDGET_REQUIRES_RECONCILIATION");
                if(rows.getString(1)!=null) period=rows.getString(1);
            }
            try(var statement=SecurityDatabase.statement(connection,"INSERT INTO token_budget_period(tenant_id,period_id,token_limit,call_limit) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",actor.tenantId(),period,policy.periodTokens(),policy.periodCalls())) { statement.executeUpdate(); }
            try(var statement=SecurityDatabase.statement(connection,"SELECT token_limit,call_limit FROM token_budget_period WHERE tenant_id=? AND period_id=?",actor.tenantId(),period);var rows=statement.executeQuery()) {
                if(!rows.next()||rows.getLong(1)!=policy.periodTokens()||rows.getInt(2)!=policy.periodCalls()) throw new IllegalStateException("TENANT_BUDGET_POLICY_MISMATCH");
            }
            try(var statement=SecurityDatabase.statement(connection,"UPDATE token_budget_account SET period_id=? WHERE tenant_id=? AND account_id=? AND period_id IS NULL",period,actor.tenantId(),runId)) { statement.executeUpdate(); }
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
