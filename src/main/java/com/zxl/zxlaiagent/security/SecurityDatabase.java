package com.zxl.zxlaiagent.security;
import org.flywaydb.core.Flyway;
import java.sql.*;
public final class SecurityDatabase implements AutoCloseable {
 private com.zaxxer.hikari.HikariDataSource pool;
 private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
 private final String url,user,password;
 private final String connectionIdentity;
 /** Reject a knowledge transaction connected to any other physical PostgreSQL database. */
 void requireSameDatabase(Connection candidate)throws SQLException{
  if(closed.get())throw new SecurityFailure("AUTHORITY_UNAVAILABLE",503);
  if(!connectionIdentity.equals(identity(candidate)))throw new SecurityFailure("AUTHORITY_DATABASE_MISMATCH",503);
 }
 /** Initialization belongs to trusted startup, never a budgeted request or a request-held monitor. */
 private String initializeIdentity(){
  try(var own=DriverManager.getConnection(url,user,password)){
   own.setNetworkTimeout(Runnable::run,5000);return identity(own);
  }catch(SQLException unavailable){throw new SecurityFailure("AUTHORITY_UNAVAILABLE",503);}
 }
 private static String identity(Connection connection)throws SQLException{
  try(var s=connection.createStatement();var r=s.executeQuery("SELECT current_database(),inet_server_addr()::text,inet_server_port()")){
   if(!r.next()||r.getString(2)==null)throw new SecurityFailure("AUTHORITY_DATABASE_MISMATCH",503);
   return r.getString(1)+"\n"+r.getString(2)+"\n"+r.getInt(3);
  }
 }
 public SecurityDatabase(String url,String user,String password){if(url==null||!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("Security requires explicit PostgreSQL configuration");this.url=url;this.user=user;this.password=password;Flyway.configure().dataSource(url,user,password).schemas("lesson17").defaultSchema("lesson17").locations("classpath:db/lesson17").table("lesson17_schema_history").load().migrate();connectionIdentity=initializeIdentity();}
 public static SecurityDatabase fromEnvironment(){return new SecurityDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));}
 /** Deployment-owned factory; pool parameters are trusted Java configuration, never model arguments. */
 public static SecurityDatabase pooledFromEnvironment(){return pooled(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"),4,1000);}
 public static SecurityDatabase pooled(String url,String user,String password,int maximum,long acquireTimeoutMs){
  if(maximum<1||maximum>8||acquireTimeoutMs<250||acquireTimeoutMs>5000)throw new IllegalArgumentException("Bounded authority pool configuration required");
  var db=new SecurityDatabase(url,user,password);
  var owned=new com.zaxxer.hikari.HikariDataSource();
  try{
   owned.setJdbcUrl(url);owned.setUsername(user);owned.setPassword(password);
   owned.setPoolName("zxl-authority-"+java.util.UUID.randomUUID());
   owned.setMaximumPoolSize(maximum);owned.setMinimumIdle(0);owned.setConnectionTimeout(acquireTimeoutMs);
   owned.setValidationTimeout(250);owned.setInitializationFailTimeout(5000);owned.setIdleTimeout(30000);owned.setMaxLifetime(300000);
   // DISCARD ALL clears server-side prepared plans; never retain driver server-plan assumptions.
   owned.addDataSourceProperty("prepareThreshold","0");
   try(var checked=owned.getConnection()){db.requireSameDatabase(checked);}
   db.pool=owned;return db;
  }catch(SQLException|RuntimeException failure){owned.close();db.close();throw new SecurityFailure("AUTHORITY_UNAVAILABLE",503);}
 }
 @Override public void close(){if(closed.compareAndSet(false,true)&&pool!=null)pool.close();}
 private Connection acquire()throws SQLException{
  if(closed.get())throw new SecurityFailure("AUTHORITY_UNAVAILABLE",503);
  return pool==null?DriverManager.getConnection(url,user,password):pool.getConnection();
 }
 private void clean(Connection c)throws SQLException{
  if(pool==null)return;
  try{
   c.setNetworkTimeout(Runnable::run,5000);c.setAutoCommit(true);
   // Not merely RESET ALL: temp tables, advisory locks, role/auth, LISTEN and plans must not cross actors.
   try(var s=c.createStatement()){s.execute("DISCARD ALL");}
  }catch(SQLException|RuntimeException failure){pool.evictConnection(c);throw failure;}
 }
 @FunctionalInterface public interface Work<T>{T run(Connection c)throws SQLException;}
 public <T>T tx(Work<T> work){
  try(var c=acquire()){
   Throwable primary=null;boolean rollbackSucceeded=true;
   try{
    c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("SET LOCAL search_path TO lesson17,public");s.execute("SET LOCAL statement_timeout='5s'");s.execute("SET LOCAL lock_timeout='3s'");}
    T value=work.run(c);c.commit();return value;
   }catch(SQLException|RuntimeException|Error failure){
    primary=failure;
    try{c.rollback();}
    catch(SQLException|RuntimeException rollback){
     rollbackSucceeded=false;failure.addSuppressed(rollback);
     // Enabling auto-commit after a failed rollback may commit failed business work.
     // Discard this physical connection instead; never run session cleanup on it.
     try{if(pool!=null)pool.evictConnection(c);else c.abort(Runnable::run);}
     catch(SQLException|RuntimeException eviction){
      failure.addSuppressed(eviction);
      try{c.abort(Runnable::run);}catch(SQLException|RuntimeException abort){failure.addSuppressed(abort);}
     }
    }
    throw failure;
   }
   finally{if(rollbackSucceeded)try{clean(c);}catch(SQLException|RuntimeException cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
  }catch(SQLException unavailable){throw new SecurityFailure("AUTHORITY_UNAVAILABLE",503);}
 }
 public static PreparedStatement statement(Connection c,String sql,Object...args)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(5);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
}
