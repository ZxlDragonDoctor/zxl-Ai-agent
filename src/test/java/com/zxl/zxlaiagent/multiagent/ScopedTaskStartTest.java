package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.agents.AbstractBaseAgent;
import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedTaskStartTest {
    @Test void cancellationInsideStartGatePreventsClaimAndExecution(){verifyStart(false);}
    @Test void deniedActualStartPreventsClaimAndExecution(){verifyStart(true);}
    void verifyStart(boolean deny){
        var control=new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(5),System::nanoTime);
        var starts=new AtomicInteger();var work=new AtomicInteger();var run=new WorkflowRun("synthetic",r->{});
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase phase){}
            public <T>T start(Phase phase,Supplier<T> action){
                if(phase==Phase.DEQUEUE){starts.incrementAndGet();if(deny)throw new SecurityFailure("ACTION_DENIED",403);control.cancel();}
                return action.get();
            }
        };
        try(var runtime=new ExecutionRuntime(new RuntimeSettings())){
            var task=Task.create("synthetic");var registry=new RunRegistry(new RuntimeSettings());
            var entry=registry.register(run,control,System.nanoTime(),boundary,new TrustedRunContext(new ActorRef("subject","tenant","session",1),run.id,new PrivateRunEvents()));
            runtime.bind(task,entry);
            var agent=new AbstractBaseAgent("synthetic","code",mock(ChatModel.class)){
                public CompletableFuture<String> processTask(Task current){work.incrementAndGet();return CompletableFuture.completedFuture("must not run");}
            };
            agent.attachRuntime(runtime);agent.requireScopedExecution();
            assertThrows(CompletionException.class,()->agent.executeTask(task).join());
            assertEquals(1,starts.get());assertEquals(0,work.get());assertEquals(TaskStatus.PENDING,task.snapshot().status());
            assertTrue(entry.trustedContext.events().snapshot().isEmpty());assertEquals(AgentState.IDLE,agent.getState());
        }
    }
}
