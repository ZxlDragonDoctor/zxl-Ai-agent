package com.zxl.zxlaiagent.knowledge;

import org.flywaydb.core.Flyway;
import java.sql.*;

public final class KnowledgeDatabase {
    private final String url,user,password;
    public KnowledgeDatabase(String url,String user,String password){
        if(url==null||!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("LESSON12_DB_URL must name a real PostgreSQL database");
        this.url=url;this.user=user;this.password=password;
        Flyway.configure().dataSource(url,user,password).schemas("lesson12").defaultSchema("lesson12")
                .locations("classpath:db/lesson12").table("lesson12_schema_history").load().migrate();
    }
    @FunctionalInterface public interface Work<T>{T run(Connection c)throws SQLException;}
    public <T>T tx(Work<T> work){try(var c=DriverManager.getConnection(url,user,password)){c.setAutoCommit(false);try(var st=c.createStatement()){st.execute("SET LOCAL search_path TO lesson12, public");st.execute("SET LOCAL statement_timeout = '5s'");st.execute("SET LOCAL lock_timeout = '3s'");}try{T r=work.run(c);c.commit();return r;}catch(SQLException|RuntimeException e){c.rollback();throw e;}}catch(SQLException e){throw new IllegalStateException("PostgreSQL knowledge transaction failed",e);}}
    public static PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(5);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
}
