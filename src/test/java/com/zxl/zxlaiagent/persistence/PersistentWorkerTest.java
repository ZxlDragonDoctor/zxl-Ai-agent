package com.zxl.zxlaiagent.persistence;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

class PersistentWorkerTest extends PersistenceFixture {
    @Test void startupScanRecoversDurableBacklogBeyondOneBatch() {
        var ids=new ArrayList<String>();for(int i=0;i<13;i++)ids.add(create("startup-"+i).id());
        var rebuilt=new PersistentWorkflowRepository(new LessonDatabase(url,"sa",""));
        try(var worker=new PersistentWorker(rebuilt,true)) {
            long until=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            boolean finished=false;
            while(System.nanoTime()<until) {
                if(ids.stream().allMatch(id -> rebuilt.get(id).run().status().equals("SUCCEEDED"))){finished=true;break;}
                LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
            }
            assertTrue(finished,"startup scanner should drain the durable backlog within bounded time");
            assertEquals(13,count("ticket"));assertEquals(13,count("task_attempt"));
            for(String id:ids){var snapshot=rebuilt.get(id);assertEquals(1,snapshot.attempts().size());assertEquals(4,snapshot.events().size());}
            assertEquals(0,worker.scan());
        }
    }
    @Test void disabledWorkerLeavesPersistentBacklogUntouched() {
        String id=create("disabled").id();
        try(var worker=new PersistentWorker(repository,false)) {assertEquals(0,worker.scan());}
        assertEquals("PENDING",repository.get(id).run().status());assertEquals(0,count("task_attempt"));assertEquals(0,count("ticket"));
    }
}
