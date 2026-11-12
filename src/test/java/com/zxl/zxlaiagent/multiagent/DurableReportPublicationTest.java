package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.runtime.PrivateRunEvents;
import com.zxl.zxlaiagent.multiagent.runtime.TrustedRunContext;
import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class DurableReportPublicationTest {
    @Test
    void reportAndEventBecomeVisibleOnlyAfterTheDurableCommitAndSequenceRetriesWithoutGap() {
        var run = new WorkflowRun("synthetic", report -> fail("formal run used classroom publisher"));
        var events = new PrivateRunEvents();
        var writes = new ArrayList<Long>();
        var transaction = new WorkflowRun.ReportTransaction() {
            private boolean failNext = true;
            @Override public <T> T commit(Supplier<T> mutation, Supplier<WorkflowRun.Report> snapshot) {
                T result = mutation.get();
                assertNull(run.latestReport());
                assertTrue(events.snapshot().isEmpty());
                assertEquals(1, snapshot.get().sequence());
                if (failNext) {
                    failNext = false;
                    throw new IllegalStateException("simulated rollback");
                }
                writes.add(snapshot.get().sequence());
                return result;
            }
        };
        run.configureScoped(OperationBoundary.unrestricted(), new TrustedRunContext(
                new ActorRef("subject", "tenant", "session", 1), run.id, events, transaction));
        assertThrows(IllegalStateException.class, () -> run.emit("queued", null, null));
        assertNull(run.latestReport());
        assertTrue(events.snapshot().isEmpty());
        run.emit("queued", null, null);
        assertEquals(1, run.latestReport().sequence());
        assertEquals(1, events.snapshot().size());
        assertEquals(java.util.List.of(1L), writes);
    }
}
