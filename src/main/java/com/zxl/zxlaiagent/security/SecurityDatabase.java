package com.zxl.zxlaiagent.security;
import org.flywaydb.core.Flyway;
import java.sql.*;
public final class SecurityDatabase {
 private final String url,user,password;
 private final String connectionIdentity;
 /** Reject a knowledge transaction connected to any other physical PostgreSQL database. */
 void requireSameDatabase(Connection candidate)throws SQLException{
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
 @FunctionalInterface public interface Work<T>{T run(Connection c)throws SQLException;}
 public <T>T tx(Work<T> work){try(var c=DriverManager.getConnection(url,user,password)){c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("SET LOCAL search_path TO lesson17,public");s.execute("SET LOCAL statement_timeout='5s'");s.execute("SET LOCAL lock_timeout='3s'");}try{T value=work.run(c);c.commit();return value;}catch(SQLException|RuntimeException e){c.rollback();throw e;}}catch(SQLException e){throw new SecurityFailure("AUTHORITY_UNAVAILABLE",503);}}
 public static PreparedStatement statement(Connection c,String sql,Object...args)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(5);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
}
