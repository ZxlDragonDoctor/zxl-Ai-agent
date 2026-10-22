package com.zxl.zxlaiagent.persistence;

import com.zxl.zxlaiagent.ticket.TicketCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.zxl.zxlaiagent.persistence.PersistentModels.*;
import static org.junit.jupiter.api.Assertions.*;

class PersistentWorkflowRepositoryTest extends PersistenceFixture {
    @TempDir Path temporary;

    @Test void fileDatabaseSurvivesNewRepositoryAndReplaysBusinessOperation() {
        var fileDb=new LessonDatabase("jdbc:h2:file:"+temporary.resolve("workflow").toAbsolutePath().toString().replace('\\','/'),"sa","");
        var first=new PersistentWorkflowRepository(fileDb);
        var command=new TicketCommand("restart-op","Persistent title","Persistent body");
        var run=first.create(command,600_000);
        assertTrue(first.complete(first.claim(run.id(),"first-process",60_000).orElseThrow()));
        var original=first.get(run.id());
        var rebuilt=new PersistentWorkflowRepository(new LessonDatabase("jdbc:h2:file:"+temporary.resolve("workflow").toAbsolutePath().toString().replace('\\','/'),"sa",""));
        var restored=rebuilt.get(run.id());
        assertEquals(original,restored);
        var replay=rebuilt.create(command,600_000);
        assertTrue(rebuilt.complete(rebuilt.claim(replay.id(),"restarted",60_000).orElseThrow()));
        assertEquals(original.ticket(),rebuilt.get(replay.id()).ticket());
        assertTrue(rebuilt.get(replay.id()).events().get(3).payload().contains("\"replayed\":true"));
    }

    @Test void separateJvmCanReadPreviouslyCommittedRunAndTicket() throws Exception {
        String jdbc="jdbc:h2:file:"+temporary.resolve("process-db").toAbsolutePath().toString().replace('\\','/');
        Path written=temporary.resolve("written.json"),read=temporary.resolve("read.json");
        child(jdbc,written,"write","");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var first=mapper.readTree(Files.readString(written));
        child(jdbc,read,"read",first.path("run").path("id").asText());
        assertEquals(first,mapper.readTree(Files.readString(read)));
        assertEquals("SUCCEEDED",first.path("run").path("status").asText());
        assertFalse(first.path("ticket").path("id").asText().isBlank());
    }
    private void child(String jdbc,Path result,String mode,String id) throws Exception {
        var log=temporary.resolve(mode+".log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
            System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),PersistenceProcessProbe.class.getName(),jdbc,result.toString(),mode,id)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue(process.waitFor(30,TimeUnit.SECONDS),"child process exceeded bounded wait");assertEquals(0,process.exitValue(),() -> {try{return Files.readString(log);}catch(Exception e){return e.toString();}});}
        finally {if(process.isAlive())process.destroyForcibly();}
    }

    @Test void concurrentClaimsHaveOneWinnerAndOneAttempt() throws Exception {
        var run=create("one-claim");
        var results=concurrently(8,index -> repository.claim(run.id(),"worker-"+index,60_000).isPresent());
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1,repository.get(run.id()).attempts().size());
        assertEquals(1,repository.get(run.id()).task().fenceToken());
    }

    @Test void concurrentSameOperationAcrossRunsCreatesOnlyOneTicket() throws Exception {
        var results=concurrently(6,index -> {
            var run=create("same-business-operation");
            assertTrue(repository.complete(repository.claim(run.id(),"worker-"+index,60_000).orElseThrow()));
            return repository.get(run.id()).ticket().id();
        });
        assertEquals(1,new HashSet<>(results).size());
        assertEquals(1,count("ticket"));assertEquals(1,count("business_operation"));assertEquals(6,count("agent_run"));
    }

    @Test void changedPayloadRollsBackWithoutCreatingRunOrEvent() {
        create("conflict");
        var conflict=assertThrows(Conflict.class,() -> repository.create(new TicketCommand("conflict","Different title","Local exercise"),60_000));
        assertEquals("OPERATION_CONFLICT",conflict.code);
        assertEquals(1,count("agent_run"));assertEquals(1,count("agent_task"));assertEquals(1,count("run_event"));assertEquals(1,count("business_operation"));assertEquals(0,count("ticket"));
    }

    @Test void cancellationRejectsCompletionAndRenewalAndDoesNotReverseTerminal() {
        var run=create("cancel");var claim=repository.claim(run.id(),"owner",60_000).orElseThrow();
        repository.cancel(run.id());
        assertFalse(repository.complete(claim));assertFalse(repository.renew(claim,5000));
        var cancelled=repository.get(run.id());
        assertEquals("CANCELLED",cancelled.run().status());assertEquals("CANCELLED",cancelled.attempts().get(0).status());assertNull(cancelled.ticket());
        repository.cancel(run.id());assertEquals(cancelled,repository.get(run.id()));assertTrue(repository.claim(run.id(),"late",5000).isEmpty());
    }

    @Test void originalDeadlineExpiresWithoutNewAttemptOrBudgetReset() {
        var run=create("deadline");long deadline=run.acceptedAt()-1;
        sql("UPDATE agent_run SET deadline_at=? WHERE id=?",deadline,run.id());
        assertTrue(repository.claim(run.id(),"late",5000).isEmpty());
        var timed=repository.get(run.id());assertEquals(deadline,timed.run().deadlineAt());assertEquals("TIMED_OUT",timed.run().status());
        assertEquals("DEADLINE_EXCEEDED",timed.run().errorCode());assertTrue(timed.attempts().isEmpty());assertNull(timed.ticket());
    }

    @Test void expiredTokenIsRejectedAndHistoryIsRetained() {
        var run=create("fence");var old=repository.claim(run.id(),"old",60_000).orElseThrow();expire(run.id());
        assertFalse(repository.renew(old,5000));assertFalse(repository.complete(old));
        var current=repository.claim(run.id(),"new",60_000).orElseThrow();assertEquals(old.token()+1,current.token());
        assertFalse(repository.complete(old));assertTrue(repository.renew(current,5000));assertTrue(repository.complete(current));
        var done=repository.get(run.id());assertEquals(List.of("EXPIRED","SUCCEEDED"),done.attempts().stream().map(Attempt::status).toList());
        assertEquals(1,count("ticket"));
    }

    @Test void fiveAttemptsAreBoundedAndTerminalDoesNotResurrect() {
        var run=create("attempt-limit");
        for(int i=1;i<=5;i++){assertEquals(i,repository.claim(run.id(),"owner-"+i,60_000).orElseThrow().token());expire(run.id());}
        assertTrue(repository.claim(run.id(),"sixth",5000).isEmpty());
        var failed=repository.get(run.id());assertEquals("FAILED",failed.run().status());assertEquals("ATTEMPT_LIMIT",failed.run().errorCode());assertEquals(5,failed.attempts().size());
        assertNull(failed.ticket());assertTrue(repository.recoverable(10).isEmpty());repository.cancel(run.id());assertEquals(failed,repository.get(run.id()));
    }

    @Test void repeatedScansAreBoundedAndDoNotCreateAttempts() {
        for(int i=0;i<7;i++)create("batch-"+i);
        var first=repository.recoverable(3);assertEquals(3,first.size());assertEquals(first,repository.recoverable(3));
        assertEquals(0,count("task_attempt"));assertEquals(7,count("run_event"));
        assertThrows(IllegalArgumentException.class,() -> repository.recoverable(0));assertThrows(IllegalArgumentException.class,() -> repository.recoverable(101));
    }

    @Test void forgedClaimCannotWriteAndValidOwnerCanFinish() {
        var run=create("forged");var valid=repository.claim(run.id(),"owner",60_000).orElseThrow();
        var forged=List.of(new Claim("wrong-task",run.id(),valid.owner(),valid.token()),new Claim(valid.taskId(),run.id(),"intruder",valid.token()),new Claim(valid.taskId(),run.id(),valid.owner(),valid.token()+1));
        for(var claim:forged){assertFalse(repository.complete(claim));assertFalse(repository.renew(claim,5000));}
        assertEquals(0,count("ticket"));assertTrue(repository.complete(valid));
        assertFalse(repository.complete(valid));assertFalse(repository.renew(valid,5000));
        assertEquals("SUCCEEDED",repository.cancel(run.id()).status());
    }

    @Test void lateSqlFailureRollsBackTicketOperationStateAndEventsTogether() {
        var run=create("atomic");var claim=repository.claim(run.id(),"owner",60_000).orElseThrow();
        sql("ALTER TABLE run_event ADD CONSTRAINT reject_result CHECK (event_type <> 'TICKET_RESULT')");
        assertThrows(IllegalStateException.class,() -> repository.complete(claim));
        assertEquals(0,count("ticket"));var rolledBack=repository.get(run.id());assertEquals("RUNNING",rolledBack.run().status());assertEquals(2,rolledBack.events().size());
        assertEquals("PENDING",db.transaction(c -> {try(var s=c.createStatement();var r=s.executeQuery("SELECT status FROM business_operation")){r.next();return r.getString(1);}}));
        sql("ALTER TABLE run_event DROP CONSTRAINT reject_result");
        assertTrue(repository.complete(claim));assertEquals(1,count("ticket"));assertEquals("SUCCEEDED",repository.get(run.id()).run().status());
    }

    @Test void invalidBudgetsAndLeaseDurationsDoNotWrite() {
        assertThrows(IllegalArgumentException.class,() -> repository.create(new TicketCommand("invalid","title",""),0));assertEquals(0,count("agent_run"));
        var run=create("valid");assertThrows(IllegalArgumentException.class,() -> repository.claim(run.id(),"worker",0));assertEquals(0,count("task_attempt"));
    }

    private interface ConcurrentAction<T>{T execute(int index) throws Exception;}
    private <T> List<T> concurrently(int count,ConcurrentAction<T> action) throws Exception {
        var pool=Executors.newFixedThreadPool(count);var start=new CountDownLatch(1);var ready=new CountDownLatch(count);var futures=new ArrayList<Future<T>>();
        try {for(int i=0;i<count;i++){int index=i;futures.add(pool.submit(() -> {ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("start barrier timed out");return action.execute(index);}));}
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();var results=new ArrayList<T>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));return results;
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
