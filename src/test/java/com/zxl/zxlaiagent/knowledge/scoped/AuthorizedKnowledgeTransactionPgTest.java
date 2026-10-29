package com.zxl.zxlaiagent.knowledge.scoped;

import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizedKnowledgeTransactionPgTest {
    SecurityDatabase security; KnowledgeDatabase knowledge; AuthenticationService auth;
    AuthorizationService policy; ActorRef actor; String subject;
    @BeforeEach void setup() {
        security=SecurityDatabase.fromEnvironment();
        knowledge=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        auth=new AuthenticationService(security);policy=new AuthorizationService(security);
        subject="atomic-"+UUID.randomUUID();auth.provision(subject,subject,"synthetic-password",subject,"knowledge-maintainer");
        actor=auth.login(subject,"synthetic-password",subject).actor().ref();
    }
    @AfterEach void cleanup() {
        if(subject==null)return;
        knowledge.tx(c->{try(var s=KnowledgeDatabase.statement(c,"DELETE FROM knowledge_document WHERE tenant_id=?",subject)){s.executeUpdate();}return null;});
        security.tx(c->{for(String table:List.of("authorization_audit","subject","tenant")){
            String field=table.equals("tenant")?"tenant_id":"subject_id";
            try(var s=SecurityDatabase.statement(c,"DELETE FROM "+table+" WHERE "+field+"=?",subject)){s.executeUpdate();}}
            return null;});
    }
    OperationBoundary boundary(String run){return new ActorBoundary(policy,actor,List.of(new AuthorizationService.Permission("knowledge.manage",null)),run);}
    @SuppressWarnings("unchecked") <T>T tx(String run,KnowledgeDatabase.Work<T> work) {
        try{return (T)KnowledgeDatabase.class.getMethod("tx",OperationBoundary.class,Phase.class,KnowledgeDatabase.Work.class)
            .invoke(knowledge,boundary(run),Phase.RESULT_PERSIST,work);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError("Connection-aware authorized knowledge transaction is required",e);}
    }
    int auditCount(String run){return security.tx(c->{try(var s=SecurityDatabase.statement(c,"SELECT count(*) FROM authorization_audit WHERE subject_id=? AND run_id=?",subject,run);var r=s.executeQuery()){r.next();return r.getInt(1);}});}
    void insert(Connection c,String id)throws SQLException{try(var s=KnowledgeDatabase.statement(c,"INSERT INTO knowledge_document(tenant_id,document_id,source_name,status) VALUES(?,?,?,'EMPTY')",subject,id,"synthetic.md")){s.executeUpdate();}}

    @Test void decisionAndBusinessSqlShareTransactionAndRestoreKnowledgeSchema() {
        tx("same-tx",c->{
            try(var s=c.createStatement();var r=s.executeQuery("SELECT current_schema(),count(*) FROM lesson17.authorization_audit WHERE subject_id='"+subject+"' AND run_id='same-tx' AND xmin::text::bigint=txid_current()%4294967296")){
                assertTrue(r.next());assertEquals("lesson12",r.getString(1));assertEquals(1,r.getInt(2));}
            assertEquals(0,auditCount("same-tx"),"uncommitted Decision must not be visible on another connection");
            insert(c,"one");return null;
        });
        assertEquals(1,auditCount("same-tx"));
        assertEquals(1,new KnowledgeService(knowledge,subject,new DemoKnowledgeEmbedding()).list().size());
    }
    @Test void failedBusinessSqlRollsBackWriteButCommitsDecisionAudit() {
        assertThrows(RuntimeException.class,()->tx("sql-failed",c->{insert(c,"one");try(var s=c.createStatement()){s.execute("SELECT 1/0");}return null;}));
        assertEquals(1,auditCount("sql-failed"));
        assertTrue(new KnowledgeService(knowledge,subject,new DemoKnowledgeEmbedding()).list().isEmpty());
        tx("after-error",c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT current_schema()")){r.next();assertEquals("lesson12",r.getString(1));}return null;});
    }
    @Test void revokedActorCannotExecuteSqlAndDeniedAuditCommits() {
        auth.revoke(actor);var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(SecurityFailure.class,()->tx("denied",c->{calls.incrementAndGet();insert(c,"one");return null;}));
        assertEquals(0,calls.get());assertEquals(1,auditCount("denied"));
    }
    @Test void runtimeFailureRollsBackBusinessWritesWithoutLosingOriginalFailure() {
        var original=new IllegalArgumentException("synthetic-business-failure");
        assertSame(original,assertThrows(IllegalArgumentException.class,()->tx("runtime-failed",c->{insert(c,"one");throw original;})));
        assertEquals(1,auditCount("runtime-failed"));
        assertTrue(new KnowledgeService(knowledge,subject,new DemoKnowledgeEmbedding()).list().isEmpty());
    }
    @Test void anotherPhysicalDatabaseIsRejectedBeforeAnyBusinessCallback()throws Exception {
        String other=security.tx(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT datname FROM pg_database WHERE datallowconn AND datname<>current_database() AND has_database_privilege(current_user,datname,'CONNECT') ORDER BY (datname='postgres') DESC,datname LIMIT 1")){
            assertTrue(r.next(),"A second connectable database is required for the physical database mismatch test");return r.getString(1);}});
        var original=java.net.URI.create(System.getenv("LESSON12_DB_URL").substring(5));
        var alternate=new java.net.URI(original.getScheme(),original.getUserInfo(),original.getHost(),original.getPort(),"/"+other,original.getQuery(),original.getFragment());
        try(var c=DriverManager.getConnection("jdbc:"+alternate,System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"))){
            c.setAutoCommit(false);var calls=new java.util.concurrent.atomic.AtomicInteger();
            var denied=assertThrows(SecurityFailure.class,()->boundary("other-db").sql(c,Phase.RESULT_PERSIST,connection->{calls.incrementAndGet();return null;}));
            assertEquals("AUTHORITY_DATABASE_MISMATCH",denied.code());assertEquals(0,calls.get());c.rollback();
        }
        assertEquals(0,auditCount("other-db"),"No audit may be written into the wrong physical authority database");
    }
    @Test void failedSqlRestoresCustomSearchPathAndLeavesAuditTransactionUsable() {
        var outcome=knowledge.tx(c->{
            String path="lesson12, pg_catalog";
            try(var s=c.prepareStatement("SELECT set_config('search_path',?,true)")){s.setString(1,path);s.execute();}
            var result=boundary("error-schema").sql(c,Phase.RESULT_PERSIST,connection->{
                insert(connection,"one");try(var s=connection.createStatement()){s.execute("SELECT 1/0");}return null;
            });
            try(var s=c.createStatement();var r=s.executeQuery("SHOW search_path")){assertTrue(r.next());assertEquals(path,r.getString(1));}
            try(var s=KnowledgeDatabase.statement(c,"SELECT count(*) FROM lesson17.authorization_audit WHERE subject_id=? AND run_id='error-schema'",subject);var r=s.executeQuery()){
                r.next();assertEquals(1,r.getInt(1));}
            return result;
        });
        assertThrows(IllegalStateException.class,outcome::result);assertEquals(1,auditCount("error-schema"));
        assertTrue(new KnowledgeService(knowledge,subject,new DemoKnowledgeEmbedding()).list().isEmpty());
    }
    @Test void revocationCannotCommitUntilBusinessTransactionCommits()throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var work=pool.submit(()->tx("concurrent",c->{insert(c,"one");started.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertTrue(started.await(3,TimeUnit.SECONDS));var revoking=pool.submit(()->auth.revoke(actor));
            assertThrows(TimeoutException.class,()->revoking.get(150,TimeUnit.MILLISECONDS));
            release.countDown();work.get(5,TimeUnit.SECONDS);revoking.get(5,TimeUnit.SECONDS);
            assertThrows(SecurityFailure.class,()->tx("after-revocation",c->{insert(c,"two");return null;}));
        } finally {release.countDown();pool.shutdownNow();}
    }
}
