package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.multiagent.runtime.ExecutionControl;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import com.zxl.zxlaiagent.multiagent.runtime.retry.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TicketReliabilityTest {
    private ExecutionControl control() { return new ExecutionControl(0, Duration.ofSeconds(10), System::nanoTime); }
    private RetryExecutor retries() { return new RetryExecutor(new RetryPolicy(3, 10, 100), millis -> {}, () -> 0.5); }
    private TicketCommand command(String id) { return new TicketCommand(id, "VPN 无法连接", "已尝试重启客户端"); }

    @Test void lostResponseRetriesSameOperationWithoutDuplicatingWrite() {
        var gateway = new InMemoryTicketGateway(10);
        var calls = new AtomicInteger();
        var service = new TicketService((command, control) -> {
            var ticket = gateway.create(command, control);
            if (calls.incrementAndGet() == 1) throw RetryFailure.transientFailure("RESPONSE_LOST", "响应丢失");
            return ticket;
        }, retries());
        var result = service.create(command("op-1"), control());
        assertEquals(2, result.attempts());
        assertEquals(1, gateway.ticketCount());
        assertEquals(result.ticket(), service.create(command("op-1"), control()).ticket());
    }

    @Test void concurrentDuplicatesReturnOneTicketAndChangedPayloadConflicts() throws Exception {
        var gateway = new InMemoryTicketGateway(10);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<Ticket>>();
            for (int i = 0; i < 30; i++) jobs.add(() -> gateway.create(command("same"), control()));
            var results = pool.invokeAll(jobs);
            var first = results.get(0).get();
            for (var result : results) assertEquals(first, result.get());
            assertEquals(1, gateway.ticketCount());
            var failure = assertThrows(RetryFailure.class, () -> gateway.create(new TicketCommand("same", "其他标题", "详情"), control()));
            assertEquals("OPERATION_CONFLICT", failure.code());
            assertFalse(failure.retryable());
        } finally { pool.shutdownNow(); }
    }

    @Test void permanentValidationFailureDoesNotRetry() {
        var calls = new AtomicInteger();
        var service = new TicketService((command, control) -> {
            calls.incrementAndGet();
            throw RetryFailure.permanentFailure("INVALID_TICKET", "缺少标题");
        }, retries());
        var failure = assertThrows(RetryFailure.class, () -> service.create(command("bad"), control()));
        assertEquals("INVALID_TICKET", failure.code());
        assertEquals(1, calls.get());
    }

    @Test void transientFailureUsesBoundedJitterAndStopsAtAttemptLimit() {
        var delays = new ArrayList<Long>();
        var executor = new RetryExecutor(new RetryPolicy(3, 10, 15), delays::add, () -> 0.5);
        var calls = new AtomicInteger();
        var failure = assertThrows(RetryFailure.class, () -> executor.execute(() -> {
            calls.incrementAndGet(); throw RetryFailure.transientFailure("UNAVAILABLE", "暂不可用");
        }, control()));
        assertEquals("RETRY_EXHAUSTED", failure.code());
        assertEquals(3, calls.get());
        assertEquals(java.util.List.of(5L, 7L), delays);
    }

    @Test void deadlineOrCancellationDuringBackoffPreventsNextAttempt() {
        var nanos = new AtomicLong();
        var clockControl = new ExecutionControl(0, Duration.ofMillis(20), nanos::get);
        var calls = new AtomicInteger();
        var executor = new RetryExecutor(new RetryPolicy(3, 10, 100), millis -> nanos.addAndGet(Duration.ofMillis(30).toNanos()), () -> 0.5);
        var error = assertThrows(WorkflowException.class, () -> executor.execute(() -> {
            calls.incrementAndGet(); throw RetryFailure.transientFailure("UNAVAILABLE", "暂不可用");
        }, clockControl));
        assertEquals("RUN_DEADLINE_EXCEEDED", error.getCode());
        assertEquals(1, calls.get());
        var cancelled = control();
        var cancelExecutor = new RetryExecutor(new RetryPolicy(3, 10, 100), millis -> cancelled.cancel(), () -> 0.5);
        assertEquals("RUN_CANCELLED", assertThrows(WorkflowException.class, () -> cancelExecutor.execute(() -> {
            throw RetryFailure.transientFailure("UNAVAILABLE", "暂不可用");
        }, cancelled)).getCode());
    }

    @Test void unknownFailureIsNotRetriedAndLedgerRetainsUncertainOperation() {
        var ledger = new OperationLedger<String>(10);
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ledger.execute("unknown", "digest", () -> {
            calls.incrementAndGet(); throw new IllegalStateException("unknown side effect");
        }));
        var error = assertThrows(RetryFailure.class, () -> ledger.execute("unknown", "digest", () -> "duplicate"));
        assertEquals("OPERATION_UNCERTAIN", error.code());
        assertEquals(1, calls.get());
    }

    @Test void capacityRefusesNewOperationButRetainsReplay() {
        var gateway = new InMemoryTicketGateway(1);
        var ticket = gateway.create(command("first"), control());
        assertEquals("OPERATION_CAPACITY_EXCEEDED", assertThrows(RetryFailure.class,
                () -> gateway.create(command("second"), control())).code());
        assertEquals(ticket, gateway.create(command("first"), control()));
        assertEquals(1, gateway.ticketCount());
    }

    @Test void retryBudgetTooSmallForBackoffDoesNotStartSecondAttempt() {
        var nanos = new AtomicLong();
        var control = new ExecutionControl(0, Duration.ofMillis(2), nanos::get);
        var executor = new RetryExecutor(new RetryPolicy(3, 10, 100), millis -> fail("should not sleep"), () -> 0.9);
        var error = assertThrows(RetryFailure.class, () -> executor.execute(() -> {
            throw RetryFailure.transientFailure("UNAVAILABLE", "暂不可用");
        }, control));
        assertEquals("RETRY_BUDGET_EXHAUSTED", error.code());
    }

    @Test void knownFailureBeforeWriteCanRetryAndPermanentOutcomeIsRetained() {
        var ledger = new OperationLedger<String>(10);
        assertThrows(RetryFailure.class, () -> ledger.execute("temporary", "v1", () -> {
            throw RetryFailure.transientFailure("BUSY_BEFORE_WRITE", "写入之前繁忙");
        }));
        assertEquals("created", ledger.execute("temporary", "v1", () -> "created"));
        assertThrows(RetryFailure.class, () -> ledger.execute("invalid", "v1", () -> {
            throw RetryFailure.permanentFailure("REJECTED", "业务拒绝");
        }));
        assertEquals("REJECTED", assertThrows(RetryFailure.class, () -> ledger.execute("invalid", "v1", () -> "must not run")).code());
    }

    @Test void unknownExceptionIsNeverRetriedAndInterruptedSleepPreservesInterrupt() {
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> retries().execute(() -> {
            calls.incrementAndGet(); throw new IllegalStateException("unclassified");
        }, control()));
        assertEquals(1, calls.get());
        var executor = new RetryExecutor(new RetryPolicy(3, 10, 100), millis -> { throw new InterruptedException(); }, () -> 0.5);
        try {
            assertEquals("EXECUTION_INTERRUPTED", assertThrows(WorkflowException.class, () -> executor.execute(() -> {
                throw RetryFailure.transientFailure("UNAVAILABLE", "不可用");
            }, control())).getCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
