package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionControlTest {
    @Test void monotonicDeadlineIncludesQueueTimeAndChildCannotExtendIt() {
        AtomicLong nanos = new AtomicLong(100);
        ExecutionControl control = new ExecutionControl(5000, Duration.ofSeconds(2), nanos::get);
        assertEquals(7000, control.deadlineAt());
        nanos.addAndGet(1_500_000_000);
        assertEquals(500, control.waitMillis(Duration.ofSeconds(60)));
        assertEquals(100, control.waitMillis(Duration.ofMillis(100)));
        nanos.addAndGet(500_000_000);
        WorkflowException error = assertThrows(WorkflowException.class, control::check);
        assertEquals("RUN_DEADLINE_EXCEEDED", error.getCode());
    }
    @Test void firstStopWinsAndBoundaryRejectsNewActions() {
        ExecutionControl control = new ExecutionControl(1, Duration.ofSeconds(3), System::nanoTime);
        assertTrue(control.cancel()); assertFalse(control.cancel());
        AtomicInteger calls = new AtomicInteger();
        assertThrows(WorkflowException.class, () -> control.admit(calls::incrementAndGet));
        assertEquals(0, calls.get());
        assertEquals("RUN_CANCELLED", control.stopReason().code());
    }
    @Test void leaseReturnsResourceExactlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        ExecutionLease lease = new ExecutionLease(releases::incrementAndGet);
        lease.close(); lease.close();
        assertEquals(1, releases.get());
    }
    @Test void requestedBudgetHasServerBounds() {
        RuntimeSettings settings = new RuntimeSettings();
        assertEquals(90000, settings.budget(null));
        assertThrows(WorkflowException.class, () -> settings.budget(0L));
        assertThrows(WorkflowException.class, () -> settings.budget(180001L));
    }
}
