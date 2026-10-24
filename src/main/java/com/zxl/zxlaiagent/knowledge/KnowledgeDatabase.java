package com.zxl.zxlaiagent.knowledge;

import org.flywaydb.core.Flyway;
import java.sql.*;

public final class KnowledgeDatabase {
    private static final java.util.concurrent.ScheduledExecutorService RETRIEVAL_CANCELLATION =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "retrieval-sql-cancellation"); thread.setDaemon(true); return thread;
            });
    private final String url,user,password;
    public KnowledgeDatabase(String url,String user,String password){
        if(url==null||!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("LESSON12_DB_URL must name a real PostgreSQL database");
        this.url=url;this.user=user;this.password=password;
        Flyway.configure().dataSource(url,user,password).schemas("lesson12").defaultSchema("lesson12")
                .locations("classpath:db/lesson12").table("lesson12_schema_history").load().migrate();
    }
    @FunctionalInterface public interface Work<T>{T run(Connection c)throws SQLException;}
    /** Retrieval uses its remaining end-to-end budget rather than the ingestion timeout. */
    public <T> T tx(java.time.Duration remaining, Work<T> work) {
        if (remaining.isNegative() || remaining.isZero() || Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Database request cancelled or expired");
        long end = System.nanoTime() + remaining.toNanos();
        var properties = new java.util.Properties();
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        properties.setProperty("connectTimeout", Long.toString(Math.max(1, (remaining.toMillis()+999)/1000)));
        try (var connection = BudgetedConnection.acquire(remaining, () -> DriverManager.getConnection(url, properties))) {
            long milliseconds = Math.max(0, (end-System.nanoTime())/1_000_000);
            if (milliseconds == 0 || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Database request cancelled or expired");
            connection.setNetworkTimeout(Runnable::run, (int) Math.min(Integer.MAX_VALUE,milliseconds));
            connection.setAutoCommit(false);
            Thread caller = Thread.currentThread();
            // JDBC socket reads do not react to Thread.interrupt. Close only this short,
            // request-owned connection when its caller cancels or the global budget expires.
            var cancellation = RETRIEVAL_CANCELLATION.scheduleAtFixedRate(() -> {
                if (caller.isInterrupted() || System.nanoTime() >= end) {
                    try { connection.abort(Runnable::run); } catch (SQLException ignored) { }
                }
            }, 25, 25, java.util.concurrent.TimeUnit.MILLISECONDS);
            try {
                try (var setting = connection.prepareStatement("SELECT set_config('search_path','lesson12,public',true), set_config('statement_timeout',?,true), set_config('lock_timeout',?,true)")) {
                    setting.setString(1, milliseconds + "ms"); setting.setString(2, milliseconds + "ms"); setting.execute();
                }
                T result = work.run(connection);
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= end) throw new IllegalStateException("Database request cancelled or expired");
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                cancellation.cancel(false);
            }
        } catch (SQLException failure) { throw new IllegalStateException("PostgreSQL retrieval transaction failed", failure); }
    }
    public <T>T tx(Work<T> work){try(var c=DriverManager.getConnection(url,user,password)){c.setAutoCommit(false);try(var st=c.createStatement()){st.execute("SET LOCAL search_path TO lesson12, public");st.execute("SET LOCAL statement_timeout = '5s'");st.execute("SET LOCAL lock_timeout = '3s'");}try{T r=work.run(c);c.commit();return r;}catch(SQLException|RuntimeException e){c.rollback();throw e;}}catch(SQLException e){throw new IllegalStateException("PostgreSQL knowledge transaction failed",e);}}
    public static PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(5);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
}
