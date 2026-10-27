package com.zxl.zxlaiagent.security;
import org.flywaydb.core.Flyway;
import java.sql.*;
public final class SecurityDatabase {
 private final String url,user,password;
 public SecurityDatabase(String url,String user,String password){if(url==null||!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("Security requires explicit PostgreSQL configuration");this.url=url;this.user=user;this.password=password;Flyway.configure().dataSource(url,user,password).schemas("lesson17").defaultSchema("lesson17").locations("classpath:db/lesson17").table("lesson17_schema_history").load().migrate();}
 public static SecurityDatabase fromEnvironment(){return new SecurityDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));}
 @FunctionalInterface public interface Work<T>{T run(Connection c)throws SQLException;}
 public <T>T tx(Work<T> work){try(var c=DriverManager.getConnection(url,user,password)){c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("SET LOCAL search_path TO lesson17,public");s.execute("SET LOCAL statement_timeout='5s'");s.execute("SET LOCAL lock_timeout='3s'");}try{T value=work.run(c);c.commit();return value;}catch(SQLException|RuntimeException e){c.rollback();throw e;}}catch(SQLException e){throw new SecurityFailure("AUTHORITY_UNAVAILABLE",503);}}
 public static PreparedStatement statement(Connection c,String sql,Object...args)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(5);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
}
