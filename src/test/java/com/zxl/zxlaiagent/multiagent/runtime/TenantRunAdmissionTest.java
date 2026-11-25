package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantRunAdmissionTest {
    @Test void tenantCapacityIsConfigurableAndMustBePositive() {
        var s=new RuntimeSettings();assertEquals(2,s.getTenantRunCapacity());
        s.setTenantRunCapacity(0);assertThrows(IllegalArgumentException.class,s::validate);
        s.setTenantRunCapacity(-1);assertThrows(IllegalArgumentException.class,s::validate);
        s.setTenantRunCapacity(1);s.validate();var r=new RunRegistry(s);register(r,"A","subject1");
        assertThrows(WorkflowException.class,()->register(r,"A","subject2"));
        assertNotNull(register(r,"B","subject1"));
    }
    @Test void differentSubjectsShareTenantLimitWhileAnotherTenantCanEnter() {
        var registry=new RunRegistry(new RuntimeSettings());
        register(registry,"A","subject1");register(registry,"A","subject2");
        var failure=assertThrows(WorkflowException.class,()->register(registry,"A","subject3"));
        assertEquals("AGENT_TENANT_CAPACITY_EXCEEDED",failure.getCode());
        assertEquals("runs.tenant",failure.getPath());
        assertFalse(failure.getMessage().contains("A"));
        assertNotNull(register(registry,"B","subject1"));
        assertEquals(3,registry.entries().size());
    }
    @Test void terminalTaskOrModelOwnershipAndCoordinatorStillConsumeTenantAdmission() {
        for(String owner:new String[]{"task","model","coordinator"}){
            var registry=new RunRegistry(new RuntimeSettings());
            var entry=register(registry,"A","subject1");register(registry,"A","subject2");
            var lease=owner.equals("task")?entry.taskLease():owner.equals("model")?entry.modelLease():new ExecutionLease(()->{});
            if(owner.equals("coordinator"))entry.coordinating=true;
            entry.run.finishFailure(new WorkflowError("RUN_CANCELLED","runtime","cancelled"));
            assertTrue(entry.view().cleanupPending());
            assertThrows(WorkflowException.class,()->register(registry,"A","subject3"),owner);
            lease.close();entry.coordinationFinished();
            assertFalse(entry.view().cleanupPending());
            assertNotNull(register(registry,"A","subject3"));
            assertEquals(3,registry.entries().size(),"retained terminal history is not an active admission");
        }
    }
    @Test void concurrentTenantRegistrationCannotExceedDefaultLimit()throws Exception {
        var registry=new RunRegistry(new RuntimeSettings());
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
        try{
            var jobs=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<4;i++)jobs.add(pool.submit(()->{assertTrue(start.await(3,TimeUnit.SECONDS));try{register(registry,"A","subject");return true;}catch(WorkflowException failure){assertEquals("AGENT_TENANT_CAPACITY_EXCEEDED",failure.getCode());return false;}}));
            start.countDown();int accepted=0;for(var job:jobs)if(job.get(3,TimeUnit.SECONDS))accepted++;
            assertEquals(2,accepted);assertEquals(2,registry.entries().size());
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void legacyAndBoundaryOnlyRunsRetainGlobalCapacityBehavior() {
        var s=new RuntimeSettings();s.setConcurrentRuns(1);s.setRunQueueCapacity(1);s.setRegistryCapacity(3);
        var r=new RunRegistry(s);r.register(run(),control());r.register(run(),control(),0,OperationBoundary.unrestricted());register(r,"A","subject");
        assertEquals("AGENT_CAPACITY_EXCEEDED",assertThrows(WorkflowException.class,()->register(r,"B","subject")).getCode());
        assertEquals(3,r.entries().size());
    }
    private static RunRegistry.Entry register(RunRegistry registry,String tenant,String subject){
        var run=run();return registry.register(run,control(),System.nanoTime(),OperationBoundary.unrestricted(),new TrustedRunContext(new ActorRef(subject,tenant,"session",1),run.id,new PrivateRunEvents()));
    }
    private static WorkflowRun run(){return new WorkflowRun("synthetic",r->{});}
    private static ExecutionControl control(){return new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(10),System::nanoTime);}
}
