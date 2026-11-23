package com.zxl.zxlaiagent.persistence;

import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.knowledge.KnowledgeDatabase;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSchemaPgTest {
 @Test void runtimeIdentityReadsWritesButCannotMigrate() throws Exception {
  String base=System.getenv("LESSON12_DB_URL"),admin=System.getenv("LESSON12_DB_USER"),secret=System.getenv("LESSON12_DB_PASSWORD");
  assertNotNull(base,"Explicit PostgreSQL required");
  String suffix=UUID.randomUUID().toString().replace("-",""), database="l24_"+suffix,role="r24_"+suffix,password=UUID.randomUUID().toString();
  String url=base.replaceFirst("/[^/?]+(?:\\?.*)?$","/"+database+"?sslmode=disable");
  try(var root=DriverManager.getConnection(base,admin,secret);var statement=root.createStatement()) {
   statement.execute("CREATE DATABASE "+database);
   statement.execute("CREATE ROLE "+role+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
   try {
    var empty=org.flywaydb.core.Flyway.configure().dataSource(url,admin,secret).schemas("lesson17").defaultSchema("lesson17").locations("classpath:db/lesson17").table("lesson17_schema_history").load();
    assertThrows(IllegalStateException.class,()->DatabaseSchemaLifecycle.apply(empty,DatabaseSchemaLifecycle.Mode.VALIDATE));
    new SecurityDatabase(url,admin,secret).close();new KnowledgeDatabase(url,admin,secret);
    try(var owner=DriverManager.getConnection(url,admin,secret);var s=owner.createStatement()) {
     s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
     for(String schema:new String[]{"lesson12","lesson17"}) {
      s.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
      s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA "+schema+" TO "+role);
      s.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA "+schema+" TO "+role);
     }
     s.execute("REVOKE ALL ON lesson17.lesson17_schema_history,lesson12.lesson12_schema_history FROM "+role);
     s.execute("GRANT SELECT ON lesson17.lesson17_schema_history,lesson12.lesson12_schema_history TO "+role);
    }
    try(var db=new SecurityDatabase(url,role,password,DatabaseSchemaLifecycle.Mode.VALIDATE)) {
     db.tx(c->{try(var s=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES ('runtime-fixture')")){s.executeUpdate();}return null;});
    }
    var knowledge=new KnowledgeDatabase(url,role,password,DatabaseSchemaLifecycle.Mode.VALIDATE);
    int count=knowledge.tx(c->{try(var s=c.createStatement()){
     s.execute("INSERT INTO knowledge_document(tenant_id,document_id,source_name,status) VALUES ('runtime-fixture','doc','fixture','ACTIVE')");
     try(var r=s.executeQuery("SELECT count(*) FROM knowledge_document")){r.next();return r.getInt(1);}
    }});assertEquals(1,count);
    double distance=knowledge.tx(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT '[1,0]'::vector <=> '[1,0]'::vector")){r.next();return r.getDouble(1);}});
    assertEquals(0.0,distance);
    try(var runtime=DriverManager.getConnection(url,role,password);var s=runtime.createStatement()) {
     assertThrows(SQLException.class,()->s.execute("CREATE TABLE lesson17.forbidden(id int)"));
     assertThrows(SQLException.class,()->s.execute("ALTER TABLE lesson17.tenant ADD COLUMN forbidden int"));
     assertThrows(SQLException.class,()->s.execute("DELETE FROM lesson17.lesson17_schema_history"));
    }
    try(var owner=DriverManager.getConnection(url,admin,secret);var s=owner.createStatement()){
     s.execute("INSERT INTO lesson17.lesson17_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES (100,'99','future deployment','SQL','V99__future.sql',1,current_user,0,true)");
    }
    assertThrows(IllegalStateException.class,()->new SecurityDatabase(url,role,password,DatabaseSchemaLifecycle.Mode.VALIDATE));
    try(var owner=DriverManager.getConnection(url,admin,secret);var s=owner.createStatement()){
     s.execute("DELETE FROM lesson17.lesson17_schema_history WHERE version='99'");
     s.execute("DELETE FROM lesson17.lesson17_schema_history WHERE version='9'");
    }
    assertThrows(IllegalStateException.class,()->new SecurityDatabase(url,role,password,DatabaseSchemaLifecycle.Mode.VALIDATE));
   } finally {
    statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='"+database+"'");
    statement.execute("DROP DATABASE "+database);statement.execute("DROP ROLE "+role);
   }
  }
 }
}
