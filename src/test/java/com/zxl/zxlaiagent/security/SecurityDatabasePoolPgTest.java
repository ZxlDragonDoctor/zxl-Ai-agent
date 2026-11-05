package com.zxl.zxlaiagent.security;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityDatabasePoolPgTest {
    @Test void rollbackFailureEvictsWithoutAutoCommitOrSessionCleanup()throws Exception {
        var db=new SecurityDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        var controlled=mock(com.zaxxer.hikari.HikariDataSource.class);var connection=mock(Connection.class);var statement=mock(Statement.class);
        when(controlled.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
        var rollback=new SQLException("synthetic rollback failure");doThrow(rollback).when(connection).rollback();
        var field=SecurityDatabase.class.getDeclaredField("pool");field.setAccessible(true);field.set(db,controlled);
        var original=new IllegalStateException("synthetic business failure");
        try {
            assertSame(original,assertThrows(IllegalStateException.class,()->db.tx(c->{throw original;})));
            assertTrue(java.util.Arrays.asList(original.getSuppressed()).contains(rollback));
            verify(controlled).evictConnection(connection);
            verify(connection,never()).setAutoCommit(true);
            verify(statement,never()).execute("DISCARD ALL");
        }finally{close(db);}
    }
    private SecurityDatabase pooled() throws Exception {
        try { return (SecurityDatabase) SecurityDatabase.class.getMethod("pooled",String.class,String.class,String.class,int.class,long.class)
                .invoke(null,System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"),1,500L); }
        catch (NoSuchMethodException absent) { throw new AssertionError("Explicit bounded pooled authority factory required",absent); }
    }
    private void close(SecurityDatabase db)throws Exception { ((AutoCloseable)(Object)db).close(); }
    private int pid(SecurityDatabase db){return db.tx(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();return r.getInt(1);}});}
    @Test void sequentialTransactionsReuseConnectionAndCloseRejectsNewWork()throws Exception {
        var db=pooled();try{int first=pid(db);for(int i=0;i<6;i++)assertEquals(first,pid(db));}finally{close(db);}
        assertThrows(SecurityFailure.class,()->pid(db));
        try(var candidate=DriverManager.getConnection(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"))){
            assertThrows(SecurityFailure.class,()->db.requireSameDatabase(candidate),"Closed authority cannot authorize SQL on an independently owned knowledge connection");
        }
    }
    @Test void failedTransactionRollsBackAndRestoresSessionSettings()throws Exception {
        var db=pooled();try{
            assertThrows(IllegalStateException.class,()->db.tx(c->{
                c.setNetworkTimeout(Runnable::run,1234);
                try(var s=c.createStatement()){s.execute("CREATE TEMP TABLE pool_failure_marker(x int)");s.execute("SET search_path TO pg_catalog");}
                throw new IllegalStateException("synthetic work failure");
            }));
            db.tx(c->{assertNotEquals(1234,c.getNetworkTimeout());try(var s=c.createStatement();var r=s.executeQuery("SELECT current_schema(),to_regclass('pg_temp.pool_failure_marker')")){r.next();assertEquals("lesson17",r.getString(1));assertNull(r.getString(2));}return null;});
            db.tx(c->{try(var s=c.createStatement()){s.execute("SET application_name='synthetic_pool_session_marker'");}return null;});
            db.tx(c->{try(var s=c.createStatement();var r=s.executeQuery("SHOW application_name")){r.next();assertNotEquals("synthetic_pool_session_marker",r.getString(1));}return null;});
        }finally{close(db);}
    }
    @Test void saturationHasBoundedWaitAndReleasesCapacityAfterWork()throws Exception {
        var db=pooled();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try{
            var owner=executor.submit(()->db.tx(c->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test wait");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return 1;}));
            assertTrue(entered.await(3,TimeUnit.SECONDS));long start=System.nanoTime();assertThrows(SecurityFailure.class,()->pid(db));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2500,"Pool acquisition must not wait indefinitely");
            release.countDown();assertEquals(1,owner.get(3,TimeUnit.SECONDS));assertTrue(pid(db)>0);
        }finally{release.countDown();executor.shutdownNow();close(db);}
    }
    @Test void committedTemporaryStateAndSessionAdvisoryLocksDoNotCrossTransactions()throws Exception {
        var db=pooled();long key=java.util.UUID.randomUUID().getMostSignificantBits();
        try{
            db.tx(c->{try(var s=c.createStatement()){s.execute("CREATE TEMP TABLE pool_committed_marker(x int)");}
                try(var s=c.prepareStatement("SELECT pg_advisory_lock(?)")){s.setLong(1,key);s.execute();}return null;});
            db.tx(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('pg_temp.pool_committed_marker')")){r.next();assertNull(r.getString(1));}return null;});
            try(var other=DriverManager.getConnection(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));var s=other.prepareStatement("SELECT pg_try_advisory_lock(?)")){
                s.setLong(1,key);try(var r=s.executeQuery()){r.next();assertTrue(r.getBoolean(1),"Session lock must have been discarded before another actor borrows");}
            }
        }finally{close(db);}
    }
}
