package com.zxl.zxlaiagent.security.execution;

import com.zxl.zxlaiagent.security.Phase;
import java.util.function.Supplier;
import java.sql.Connection;
import java.sql.SQLException;

/** Explicit per-operation authorization, never a thread-local or model-controlled identity. */
public interface OperationBoundary {
    void check(Phase phase);

    /** Only sendAsync/subscribe/enqueue or a short local read/write belongs inside this callback. */
    <T> T start(Phase phase, Supplier<T> operation);

    @FunctionalInterface interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    /** A deferred failure lets the owning transaction commit its Decision audit first. */
    record SqlOutcome<T>(T value, RuntimeException failure) {
        public T result() { if (failure != null) throw failure; return value; }
    }

    /**
     * The caller owns this connection and commits exactly once after this callback.
     * Formal actor boundaries override this to authorize on this same transaction.
     * The default preserves isolated legacy/test boundaries without ambient identity.
     */
    default <T> SqlOutcome<T> sql(Connection connection, Phase phase, SqlWork<T> operation) throws SQLException {
        check(phase);
        return new SqlOutcome<>(operation.run(connection), null);
    }

    /** Compatibility for isolated legacy demonstrations and offline algorithm tests only. */
    static OperationBoundary unrestricted() {
        return new OperationBoundary() {
            public void check(Phase phase) {}
            public <T> T start(Phase phase, Supplier<T> operation) { return operation.get(); }
        };
    }
}
