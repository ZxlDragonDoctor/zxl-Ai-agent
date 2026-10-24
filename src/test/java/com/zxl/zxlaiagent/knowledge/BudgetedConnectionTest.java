package com.zxl.zxlaiagent.knowledge;

import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BudgetedConnectionTest {
    @Test void timedOutAcquisitionClosesConnectionThatArrivesLate() throws Exception {
        var release=new CountDownLatch(1); var closed=new CountDownLatch(1);
        Connection connection=mock(Connection.class);
        doAnswer(invocation->{closed.countDown();return null;}).when(connection).close();
        long started=System.nanoTime();
        assertThrows(IllegalStateException.class,()->BudgetedConnection.acquire(Duration.ofMillis(80),()->{
            awaitIgnoringInterrupt(release); return connection;
        }));
        assertTrue((System.nanoTime()-started)/1_000_000<500);
        release.countDown(); assertTrue(closed.await(1,TimeUnit.SECONDS));
    }
    @Test void callerCancellationReturnsPromptlyAndClosesLateConnection() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1); var finished=new CountDownLatch(1);
        var closed=new CountDownLatch(1); var keptInterrupt=new AtomicBoolean();
        Connection connection=mock(Connection.class);
        doAnswer(invocation->{closed.countDown();return null;}).when(connection).close();
        var caller=new Thread(()->{
            try { BudgetedConnection.acquire(Duration.ofSeconds(5),()->{started.countDown();awaitIgnoringInterrupt(release);return connection;}); }
            catch(IllegalStateException expected) { keptInterrupt.set(Thread.currentThread().isInterrupted()); }
            finally { finished.countDown(); }
        });
        caller.start(); assertTrue(started.await(1,TimeUnit.SECONDS)); caller.interrupt();
        assertTrue(finished.await(500,TimeUnit.MILLISECONDS)); assertTrue(keptInterrupt.get());
        release.countDown(); assertTrue(closed.await(1,TimeUnit.SECONDS)); caller.join(1000);
    }
    @Test void successfulConnectionOwnershipPassesToCaller() throws Exception {
        Connection connection=mock(Connection.class);
        assertSame(connection,BudgetedConnection.acquire(Duration.ofSeconds(1),()->connection));
        verify(connection,never()).close();
    }
    private static void awaitIgnoringInterrupt(CountDownLatch release) {
        boolean released=false;
        while(!released)try { release.await();released=true; } catch(InterruptedException ignored) { }
    }
}
