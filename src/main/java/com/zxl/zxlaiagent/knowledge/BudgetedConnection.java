package com.zxl.zxlaiagent.knowledge;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.*;

/** Bounds caller wait even when JDBC connectTimeout only supports whole seconds.
 * Driver handshakes may finish later; their connections are then closed instead of published.
 * The fixed worker/queue limits bound residual handshakes and reject overload explicitly.
 */
final class BudgetedConnection {
    private static final ThreadPoolExecutor ACQUISITIONS = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                var thread = new Thread(task, "retrieval-connection-acquisition");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final class Ownership { Connection connection; boolean abandoned; }

    static Connection acquire(Duration remaining, Callable<Connection> connector) {
        if (remaining == null || remaining.isNegative() || remaining.isZero() || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Connection request cancelled or expired");
        }
        var ownership = new Ownership();
        Future<Connection> pending;
        try {
            pending = ACQUISITIONS.submit(() -> {
                Connection connected = connector.call();
                synchronized (ownership) {
                    if (ownership.abandoned) { close(connected); return null; }
                    ownership.connection = connected;
                    return connected;
                }
            });
        } catch (RejectedExecutionException full) { throw new IllegalStateException("Database connection capacity exhausted", full); }
        try {
            Connection connection = pending.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
            synchronized (ownership) { ownership.connection = null; }
            return connection;
        } catch (InterruptedException interrupted) {
            abandon(ownership, pending); Thread.currentThread().interrupt();
            throw new IllegalStateException("Database connection request cancelled", interrupted);
        } catch (TimeoutException timeout) {
            abandon(ownership, pending); throw new IllegalStateException("Database connection deadline exceeded", timeout);
        } catch (ExecutionException failed) {
            abandon(ownership, pending); throw new IllegalStateException("Database connection failed", failed.getCause());
        }
    }
    private static void abandon(Ownership ownership, Future<Connection> pending) {
        synchronized (ownership) {
            ownership.abandoned = true;
            close(ownership.connection); ownership.connection = null;
        }
        pending.cancel(true); ACQUISITIONS.purge();
    }
    private static void close(Connection connection) {
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
    }
}
