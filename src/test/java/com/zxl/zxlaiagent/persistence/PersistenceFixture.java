package com.zxl.zxlaiagent.persistence;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;
import com.zxl.zxlaiagent.ticket.TicketCommand;

abstract class PersistenceFixture {
    protected String url;
    protected LessonDatabase db;
    protected PersistentWorkflowRepository repository;
    @BeforeEach void database() {
        url="jdbc:h2:mem:lesson9_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        db=new LessonDatabase(url,"sa","");repository=new PersistentWorkflowRepository(db);
    }
    @AfterEach void shutdown() throws Exception {
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){s.execute("SHUTDOWN");}
    }
    protected PersistentModels.Run create(String operation) {
        return repository.create(new TicketCommand(operation,"VPN access","Local exercise"),600_000);
    }
    protected void sql(String sql,Object... args) {
        db.transaction(c -> {try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}return null;});
    }
    protected long count(String table) {
        return db.transaction(c -> {try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});
    }
    protected void expire(String runId) {sql("UPDATE agent_task SET lease_until=0 WHERE run_id=?",runId);}
}
