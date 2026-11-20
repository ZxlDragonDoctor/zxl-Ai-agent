package com.zxl.zxlaiagent.usage;

import java.util.Objects;

/** One server-assigned call identity. Construction performs no database operation. */
public record TokenCallBudget(TokenBudgetLedger ledger, String account, String callId, long estimate)
        implements ModelCallBudget {
    public TokenCallBudget { Objects.requireNonNull(ledger); Objects.requireNonNull(account); Objects.requireNonNull(callId); }
    @Override public Reservation reserve() {
        ledger.reserve(account, callId, estimate);
        return new Reservation() {
            private boolean dispatched;
            private boolean dispatchAttempted;
            @Override public void dispatch() {
                dispatchAttempted = true;
                if (!ledger.dispatch(account, callId)) throw new IllegalStateException("BUDGET_CALL_ALREADY_DISPATCHED");
                dispatched = true;
            }
            @Override public void finish(Long actualTotal) {
                if (dispatched) {
                    if (actualTotal == null) ledger.unknown(account, callId);
                    else ledger.settle(account, callId, actualTotal);
                } else if (!dispatchAttempted) {
                    ledger.releaseUnsent(account, callId);
                }
                // A failed dispatch/replay never owns another attempt's reservation.
            }
        };
    }
}
