package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class TrustedRunContextTest {
    final ActorRef actor=new ActorRef("synthetic-user","synthetic-tenant","synthetic-session",1);
    ExecutionControl control(){return new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(5),System::nanoTime);}
    @Test void rejectsMissingTrustedFields(){
        assertThrows(NullPointerException.class,()->new TrustedRunContext(null,"run",new PrivateRunEvents()));
        assertThrows(NullPointerException.class,()->new TrustedRunContext(actor,null,new PrivateRunEvents()));
        assertThrows(NullPointerException.class,()->new TrustedRunContext(actor,"run",null));
        assertThrows(IllegalArgumentException.class,()->new TrustedRunContext(actor,"  ",new PrivateRunEvents()));
    }
    @Test void mismatchedRunIsRejectedBeforeRegistration(){
        var registry=new RunRegistry(new RuntimeSettings());
        var run=new WorkflowRun("synthetic",report->{});
        assertThrows(IllegalArgumentException.class,()->registry.register(run,control(),System.nanoTime(),OperationBoundary.unrestricted(),new TrustedRunContext(actor,"different",new PrivateRunEvents())));
        assertNull(registry.find(run.id));
        assertThrows(NullPointerException.class,()->registry.register(run,control(),System.nanoTime(),OperationBoundary.unrestricted(),null));
    }
    @Test void trustedContextIsPreservedInTheCapturedScope(){
        var registry=new RunRegistry(new RuntimeSettings());var run=new WorkflowRun("synthetic",report->{});
        var events=new PrivateRunEvents();var context=new TrustedRunContext(actor,run.id,events);
        var entry=registry.register(run,control(),System.nanoTime(),OperationBoundary.unrestricted(),context);
        assertSame(context,entry.trustedContext);
        try(var runtime=new ExecutionRuntime(new RuntimeSettings())){
            var task=Task.create("synthetic");runtime.bind(task,entry);var captured=runtime.executionScope(task);
            runtime.unbind(task);assertSame(context,captured.trustedContext());assertSame(actor,captured.trustedContext().actor());
            var event=AgentEvent.of("synthetic",EventType.TASK_STARTED);captured.trustedContext().events().accept(event);
            assertEquals(java.util.List.of(event),events.snapshot());assertNull(runtime.executionScope(task).trustedContext());
        }
    }
    @Test void oldRegistrationsRetainNullContext(){
        var registry=new RunRegistry(new RuntimeSettings());
        var legacy=registry.register(new WorkflowRun("legacy",report->{}),control());
        var scoped=registry.register(new WorkflowRun("scoped",report->{}),control(),System.nanoTime(),OperationBoundary.unrestricted());
        assertNull(legacy.trustedContext);assertFalse(legacy.scoped);assertNull(scoped.trustedContext);assertTrue(scoped.scoped);
    }
}
