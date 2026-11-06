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
