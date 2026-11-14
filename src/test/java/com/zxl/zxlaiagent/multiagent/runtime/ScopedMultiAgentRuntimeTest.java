package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.model.Task;
import com.zxl.zxlaiagent.multiagent.model.TaskLifecycle;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ScopedMultiAgentRuntimeTest {
    @Test void overlappingCallsKeepCorrelationLocalAndMetricsBounded() throws Exception {
        var settings = new RuntimeSettings();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(registry);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var rendezvous = new java.util.concurrent.CyclicBarrier(2);
        try (var runtime = new ExecutionRuntime(settings)) {
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++) {
                var entry = scoped(settings, OperationBoundary.unrestricted());
                var task = Task.create("synthetic");
                TaskLifecycle.start(task, "worker");
                runtime.bind(task, entry);
                results.add(workers.submit(() -> {
                    org.slf4j.MDC.put("agentRunId", "outer");
                    try {
                        return runtime.modelCall(task, () -> {
                            try { rendezvous.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                            catch (Exception failure) { throw new AssertionError(failure); }
                            assertEquals(entry.run.id, org.slf4j.MDC.get("agentRunId"));
                            assertEquals(task.getId(), org.slf4j.MDC.get("agentTaskId"));
                            return entry.run.id;
                        });
                    } finally {
                        assertEquals("outer", org.slf4j.MDC.get("agentRunId"));
                        assertNull(org.slf4j.MDC.get("agentTaskId"));
                        org.slf4j.MDC.clear();
                    }
                }));
            }
            assertNotEquals(results.get(0).get(), results.get(1).get());
            for (String metric : List.of("agent.model.queue", "agent.model.admitted")) {
                var timer = registry.get(metric).tag("outcome", "completed").timer();
                assertEquals(2, timer.count());
                assertEquals(List.of("outcome"), timer.getId().getTags().stream()
                        .map(io.micrometer.core.instrument.Tag::getKey).toList());
            }
        } finally {
            workers.shutdownNow();
            io.micrometer.core.instrument.Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    @Test void modelTelemetryCorrelatesTheRunWithoutLoggingFailurePayload() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ExecutionRuntime.class);
        var entries = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        entries.start();
        logger.addAppender(entries);
        var settings = new RuntimeSettings();
        try (var runtime = new ExecutionRuntime(settings)) {
            var entry = scoped(settings, boundary(new ArrayList<>(), new AtomicBoolean(true)));
            var task = Task.create("private prompt fixture");
            TaskLifecycle.start(task, "worker");
            runtime.bind(task, entry);
            assertThrows(IllegalStateException.class, () -> runtime.modelCall(task, () -> {
                throw new IllegalStateException("private response fixture");
            }));
            var messages = entries.list.stream().map(event -> event.getFormattedMessage()).toList();
            assertTrue(messages.stream().anyMatch(line -> line.contains(entry.run.id)
                    && line.contains(task.getId()) && line.contains("outcome=failed")));
            assertTrue(messages.stream().noneMatch(line -> line.contains("private")));
        } finally {
            logger.detachAppender(entries);
            entries.stop();
        }
    }

    @Test void changingModelTaskIdCannotDetachItsTrustedControl() {
        var settings=new RuntimeSettings();var registry=new RunRegistry(settings);
        var entry=registry.register(new WorkflowRun("synthetic",r->{}),new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(5),System::nanoTime));
        var task=Task.create("synthetic");
        try(var runtime=new ExecutionRuntime(settings)){
            runtime.bind(task,entry);task.setId("changed-by-model");assertSame(entry.control,runtime.control(task));
        }
    }
    @Test void unbindingDuringCallCannotDowngradeTheCapturedBoundary()throws Exception {
        var settings=new RuntimeSettings();var phases=new ArrayList<Phase>();var allowed=new AtomicBoolean(true);
        try(var runtime=new ExecutionRuntime(settings)){
            var task=Task.create("synthetic");TaskLifecycle.start(task,"worker");runtime.bind(task,scoped(settings,boundary(phases,allowed)));
            assertThrows(SecurityFailure.class,()->runtime.modelCall(task,()->{allowed.set(false);runtime.unbind(task);return "sensitive";}));
        }
    }
    @Test void modelSuppliedTaskIdsCannotOverwriteAnotherRunsControl() {
        var settings=new RuntimeSettings();var registry=new RunRegistry(settings);
        var a=registry.register(new WorkflowRun("A",r->{}),new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(5),System::nanoTime));
        var b=registry.register(new WorkflowRun("B",r->{}),new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(5),System::nanoTime));
        var first=Task.create("first");var second=Task.create("second");second.setId(first.getId());
        try(var runtime=new ExecutionRuntime(settings)){
            runtime.bind(first,a);runtime.bind(second,b);
            assertSame(a.control,runtime.control(first));assertSame(b.control,runtime.control(second));
            runtime.unbind(first);assertSame(b.control,runtime.control(second));
        }
    }
    RunRegistry.Entry scoped(RuntimeSettings settings, OperationBoundary boundary) throws Exception {
        var registry=new RunRegistry(settings);var run=new WorkflowRun("synthetic", r->{});
        var control=new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(5),System::nanoTime);
        try{return (RunRegistry.Entry) RunRegistry.class.getMethod("register",WorkflowRun.class,ExecutionControl.class,long.class,OperationBoundary.class)
                .invoke(registry,run,control,System.nanoTime(),boundary);}
        catch(NoSuchMethodException missing){throw new AssertionError("Explicit per-run authorization boundary is required",missing);}
    }
    OperationBoundary boundary(List<Phase> phases, AtomicBoolean allowed) {
        return new OperationBoundary(){
            public void check(Phase phase){phases.add(phase);if(!allowed.get())throw new SecurityFailure("ACTION_DENIED",403);}
            public <T>T start(Phase phase,Supplier<T> action){fail("Blocking model wait must not be wrapped in a PG start gate");return null;}
        };
    }
    @Test void currentRunBoundaryChecksDispatchAndFinalResultWithoutHoldingRemoteGate()throws Exception{
        var settings=new RuntimeSettings();var phases=new ArrayList<Phase>();var allowed=new AtomicBoolean(true);
        try(var runtime=new ExecutionRuntime(settings)){
            var entry=scoped(settings,boundary(phases,allowed));var task=Task.create("synthetic");TaskLifecycle.start(task,"worker");runtime.bind(task,entry);
            assertEquals("result",runtime.modelCall(task,()->"result"));
            assertTrue(phases.contains(Phase.DEQUEUE));assertTrue(phases.contains(Phase.MODEL_DISPATCH));assertTrue(phases.contains(Phase.RESULT_PUBLISH));
        }
    }
    @Test void revokedBeforeDispatchNeverCallsModel()throws Exception{
        var settings=new RuntimeSettings();var phases=new ArrayList<Phase>();var allowed=new AtomicBoolean(false);var calls=new AtomicInteger();
        try(var runtime=new ExecutionRuntime(settings)){
            var task=Task.create("synthetic");TaskLifecycle.start(task,"worker");runtime.bind(task,scoped(settings,boundary(phases,allowed)));
            assertThrows(SecurityFailure.class,()->runtime.modelCall(task,()->calls.incrementAndGet()));assertEquals(0,calls.get());
        }
    }
    @Test void revocationDuringModelWaitDiscardsReturnedSensitiveValue()throws Exception{
        var settings=new RuntimeSettings();var phases=new ArrayList<Phase>();var allowed=new AtomicBoolean(true);
        try(var runtime=new ExecutionRuntime(settings)){
            var task=Task.create("synthetic");TaskLifecycle.start(task,"worker");runtime.bind(task,scoped(settings,boundary(phases,allowed)));
            assertThrows(SecurityFailure.class,()->runtime.modelCall(task,()->{allowed.set(false);return "sensitive";}));
        }
    }
}
