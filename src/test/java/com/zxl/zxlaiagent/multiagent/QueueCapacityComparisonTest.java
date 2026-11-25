package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real executor experiment: change queue capacity alone, restore it, and keep service work latched. */
class QueueCapacityComparisonTest {
    @Test void aLargerQueueAdmitsMoreWaitingWorkWithoutIncreasingWorkers() throws Exception {
        var observations=new ArrayList<Map<String,Integer>>();
        for(int capacity:new int[]{1,4,1}) {
            var settings=new RuntimeSettings();settings.setConcurrentRuns(1);settings.setRunQueueCapacity(capacity);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var completed=new AtomicInteger();
            try(var runtime=new ExecutionRuntime(settings)) {
                try {
                    runtime.dispatch(()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");completed.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
                    assertTrue(entered.await(3,TimeUnit.SECONDS));int rejected=0;
                    for(int i=0;i<5;i++)try{runtime.dispatch(completed::incrementAndGet);}catch(WorkflowException full){assertEquals("AGENT_CAPACITY_EXCEEDED",full.getCode());rejected++;}
                    assertEquals(capacity,runtime.metrics().get("queuedRuns"));
                    assertEquals(1,runtime.metrics().get("coordinating"));
                    assertEquals(1,runtime.metrics().get("runThreads"));
                    assertEquals(5-capacity,rejected);assertEquals(0,completed.get(),"no completed work while service is blocked");
                    release.countDown();runtime.close();assertTrue(runtime.awaitTermination(3,TimeUnit.SECONDS));
                    assertEquals(1+capacity,completed.get());assertEquals(0,runtime.metrics().get("queuedRuns"));
                    observations.add(Map.of("runQueueCapacity",capacity,"offered",6,"threads",1,"rejected",rejected,"completed",completed.get()));
                }finally{release.countDown();}
            }
        }
        assertEquals(observations.get(0),observations.get(2),"restoring the only parameter restores bounded admission");
        Files.createDirectories(Path.of("target"));
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/lesson25-queue-comparison.json").toFile(),Map.of("observations",observations,"scope","Actual local executor with latch-controlled service. Only runQueueCapacity changes 1/4/1; no HTTP, PG, model, latency or throughput claim."));
    }
}
