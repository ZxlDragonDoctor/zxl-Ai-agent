package com.zxl.zxlaiagent.persistence;

import com.zxl.zxlaiagent.ticket.TicketCommand;
import java.sql.*;
import java.util.*;
import static com.zxl.zxlaiagent.persistence.PersistentModels.*;

/** Short JDBC transactions. One run owns exactly one local CREATE_TICKET task. */
public final class PersistentWorkflowRepository {
    private final LessonDatabase db;
    public PersistentWorkflowRepository(LessonDatabase db) { this.db = db; }
    private static String id() { return UUID.randomUUID().toString(); }
    private static boolean terminal(String status) { return !status.equals("PENDING") && !status.equals("RUNNING"); }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var statement=c.prepareStatement(sql);statement.setQueryTimeout(5);return statement;
    }
    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (var p = prepare(c,sql)) { bind(p, args); return p.executeUpdate(); }
    }
    private static void bind(PreparedStatement p, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
    }
    private static Run run(Connection c, String id, boolean lock) throws SQLException {
        try (var p = prepare(c,"SELECT * FROM agent_run WHERE id=?" + (lock ? " FOR UPDATE" : ""))) {
            p.setString(1,id); try(var r=p.executeQuery()) {
                if(!r.next()) throw new Conflict("RUN_NOT_FOUND");
                return new Run(id,r.getString("status"),r.getLong("accepted_at"),r.getLong("deadline_at"),r.getLong("version"),r.getString("error_code"));
            }
        }
    }
    private static Task task(Connection c, String runId) throws SQLException {
        try(var p=prepare(c,"SELECT * FROM agent_task WHERE run_id=?")) {
            p.setString(1,runId); try(var r=p.executeQuery()) {
                if(!r.next()) throw new Conflict("TASK_NOT_FOUND");
                return new Task(r.getString("id"),runId,r.getString("status"),r.getString("operation_id"),r.getString("title"),r.getString("description"),
                    r.getLong("version"),r.getString("lease_owner"),(Long)r.getObject("lease_until"),r.getLong("fence_token"));
            }
        }
    }
    // Every caller owns the run row lock; sequence allocation and events commit with its state.
    private static void event(Connection c, String runId, String type, String payload, long now) throws SQLException {
        long sequence;
        try(var p=prepare(c,"SELECT COALESCE(MAX(sequence),0)+1 FROM run_event WHERE run_id=?")) {
            p.setString(1,runId); try(var r=p.executeQuery()){r.next();sequence=r.getLong(1);}
        }
        update(c,"INSERT INTO run_event VALUES (?,?,?,?,?)",runId,sequence,type,payload,now);
    }
    public Run create(TicketCommand command, long timeoutMs) {
        if(timeoutMs<1 || timeoutMs>3_600_000) throw new IllegalArgumentException("timeoutMs must be 1..3600000");
        return db.transaction(c -> {
            long now=db.now(c); String runId=id(),taskId=id();
            // Unique insert races use a savepoint, so PostgreSQL can continue after a duplicate-key error.
            var save=c.setSavepoint();
            try { update(c,"INSERT INTO business_operation VALUES ('lesson9-demo','CREATE_TICKET',?,?,'PENDING',NULL,0)",command.operationId(),command.fingerprint()); }
            catch(SQLException e) { if(!"23505".equals(e.getSQLState())) throw e; c.rollback(save); }
            try(var p=prepare(c,"SELECT fingerprint FROM business_operation WHERE scope='lesson9-demo' AND operation_type='CREATE_TICKET' AND operation_id=? FOR UPDATE")) {
                p.setString(1,command.operationId());try(var r=p.executeQuery()) {
                    if(!r.next() || !command.fingerprint().equals(r.getString(1))) throw new Conflict("OPERATION_CONFLICT");
                }
            }
            update(c,"INSERT INTO agent_run VALUES (?,?, 'PENDING',?,?,0,?,?,NULL)",runId,command.title(),now,now+timeoutMs,now,now);
            update(c,"INSERT INTO agent_task VALUES (?,?,'CREATE_TICKET',?,?,?,?,'PENDING',0,NULL,NULL,0)",taskId,runId,command.operationId(),command.fingerprint(),command.title(),command.description());
            event(c,runId,"ACCEPTED","{}",now);
            return run(c,runId,false);
        });
    }
    public Optional<Claim> claim(String runId, String owner, long leaseMs) {
        if(leaseMs<1 || leaseMs>60_000) throw new IllegalArgumentException("leaseMs must be 1..60000");
        return db.transaction(c -> {
            Run r=run(c,runId,true);long now=db.now(c);
            if(terminal(r.status())) return Optional.empty();
            Task t=task(c,runId);
            if(r.deadlineAt()<=now) { finish(c,r,t,"TIMED_OUT","DEADLINE_EXCEEDED",now);return Optional.empty(); }
            if(t.status().equals("RUNNING") && t.leaseUntil()!=null && t.leaseUntil()>now) return Optional.empty();
            if(t.fenceToken()>=5) { finish(c,r,t,"FAILED","ATTEMPT_LIMIT",now);return Optional.empty(); }
            update(c,"UPDATE task_attempt SET status='EXPIRED',finished_at=?,error_code='LEASE_EXPIRED' WHERE task_id=? AND status='RUNNING'",now,t.id());
            long token=t.fenceToken()+1;
            int rows=update(c,"UPDATE agent_task SET status='RUNNING',lease_owner=?,lease_until=?,fence_token=?,version=version+1 WHERE id=? AND version=? AND (status='PENDING' OR (status='RUNNING' AND lease_until<=?))",
                owner,Math.min(r.deadlineAt(),now+leaseMs),token,t.id(),t.version(),now);
            if(rows!=1) throw new Conflict("CLAIM_CONFLICT");
            if(update(c,"UPDATE agent_run SET status='RUNNING',version=version+1,updated_at=? WHERE id=? AND version=?",now,runId,r.version())!=1) throw new Conflict("CLAIM_CONFLICT");
            update(c,"INSERT INTO task_attempt VALUES (?,?,?,?,'RUNNING',?,NULL,NULL)",id(),t.id(),(int)token,token,now);
            event(c,runId,"CLAIMED","{\"fenceToken\":"+token+"}",now);
            return Optional.of(new Claim(t.id(),runId,owner,token));
        });
    }
    private static boolean owns(Run r, Task t, Claim claim, long now) {
        return r.status().equals("RUNNING") && r.deadlineAt()>now && t.status().equals("RUNNING")
            && t.id().equals(claim.taskId()) && Objects.equals(t.leaseOwner(),claim.owner()) && t.fenceToken()==claim.token()
            && t.leaseUntil()!=null && t.leaseUntil()>now;
    }
    public boolean renew(Claim claim,long leaseMs) {
        if(leaseMs<1 || leaseMs>60_000) throw new IllegalArgumentException("leaseMs must be 1..60000");
        return db.transaction(c -> {
            Run r=run(c,claim.runId(),true);Task t=task(c,r.id());long now=db.now(c);
            if(!owns(r,t,claim,now)) return false;
            return update(c,"UPDATE agent_task SET lease_until=?,version=version+1 WHERE id=? AND version=? AND lease_owner=? AND fence_token=? AND lease_until>?",
                Math.min(r.deadlineAt(),now+leaseMs),t.id(),t.version(),claim.owner(),claim.token(),now)==1;
        });
    }
    public boolean complete(Claim claim) {
        return db.transaction(c -> {
            Run r=run(c,claim.runId(),true);Task t=task(c,r.id());long now=db.now(c);
            if(!owns(r,t,claim,now)) return false;
            String fingerprint=new TicketCommand(t.operationId(),t.title(),t.description()).fingerprint();
            String status,ticketId,storedFingerprint;
            try(var p=prepare(c,"SELECT fingerprint,status,ticket_id FROM business_operation WHERE scope='lesson9-demo' AND operation_type='CREATE_TICKET' AND operation_id=? FOR UPDATE")) {
                p.setString(1,t.operationId());try(var rs=p.executeQuery()) {
                    if(!rs.next()) throw new Conflict("OPERATION_NOT_FOUND");
                    storedFingerprint=rs.getString(1);status=rs.getString(2);ticketId=rs.getString(3);
                }
            }
            // Refresh time after potentially waiting for the shared operation row lock.
            now=db.now(c);
            if(!owns(r,t,claim,now)) return false;
            if(!fingerprint.equals(storedFingerprint)) {finish(c,r,t,"FAILED","OPERATION_CONFLICT",now);return false;}
            if(!status.equals("SUCCEEDED")) {
                ticketId=id();
                update(c,"INSERT INTO ticket VALUES (?,?,?,?,?)",ticketId,t.operationId(),t.title(),t.description(),now);
                update(c,"UPDATE business_operation SET status='SUCCEEDED',ticket_id=?,version=version+1 WHERE scope='lesson9-demo' AND operation_type='CREATE_TICKET' AND operation_id=?",ticketId,t.operationId());
            }
            finish(c,r,t,"SUCCEEDED",null,now);
            event(c,r.id(),"TICKET_RESULT","{\"ticketId\":\""+ticketId+"\",\"replayed\":"+status.equals("SUCCEEDED")+"}",now);
            return true;
        });
    }
    private static void finish(Connection c,Run r,Task t,String status,String error,long now) throws SQLException {
        int rows=update(c,"UPDATE agent_run SET status=?,error_code=?,updated_at=?,version=version+1 WHERE id=? AND version=? AND status IN ('PENDING','RUNNING')",status,error,now,r.id(),r.version());
        if(rows!=1) throw new Conflict("TERMINAL_CONFLICT");
        if(update(c,"UPDATE agent_task SET status=?,lease_owner=NULL,lease_until=NULL,version=version+1 WHERE id=? AND version=?",status,t.id(),t.version())!=1) throw new Conflict("TASK_VERSION_CONFLICT");
        update(c,"UPDATE task_attempt SET status=?,finished_at=?,error_code=? WHERE task_id=? AND status='RUNNING'",status,now,error,t.id());
        event(c,r.id(),status,error==null?"{}":"{\"error\":\""+error+"\"}",now);
    }
    public Run cancel(String runId) {
        return db.transaction(c -> {Run r=run(c,runId,true);
            if(!terminal(r.status())) finish(c,r,task(c,runId),"CANCELLED","USER_CANCELLED",db.now(c));
            return run(c,runId,false);
        });
    }
    /** Bounded scan; only claim() creates attempts. Duplicate scans are safe. */
    public List<String> recoverable(int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("batch must be 1..100");
        return db.transaction(c -> {
            long now=db.now(c);List<String> ids=new ArrayList<>();
            try(var p=prepare(c,"SELECT r.id FROM agent_run r JOIN agent_task t ON t.run_id=r.id WHERE r.status IN ('PENDING','RUNNING') AND (r.deadline_at<=? OR t.status='PENDING' OR t.lease_until<=?) ORDER BY r.accepted_at,r.id LIMIT ?")) {
                bind(p,now,now,limit);try(var rs=p.executeQuery()){while(rs.next())ids.add(rs.getString(1));}
            }return ids;
        });
    }
    public Snapshot get(String runId) {
        return db.transaction(c -> {
            // Lock makes the multi-query snapshot consistent with state transitions in this repository.
            Run r=run(c,runId,true);Task t=task(c,runId);List<Attempt> attempts=new ArrayList<>();List<Event> events=new ArrayList<>();Ticket ticket=null;
            try(var p=prepare(c,"SELECT * FROM task_attempt WHERE task_id=? ORDER BY attempt_no")) {
                p.setString(1,t.id());try(var rs=p.executeQuery()){while(rs.next()) attempts.add(new Attempt(rs.getString("id"),t.id(),rs.getInt("attempt_no"),rs.getLong("fence_token"),rs.getString("status"),rs.getLong("started_at"),(Long)rs.getObject("finished_at"),rs.getString("error_code")));}
            }
            try(var p=prepare(c,"SELECT * FROM run_event WHERE run_id=? ORDER BY sequence")) {
                p.setString(1,runId);try(var rs=p.executeQuery()){while(rs.next())events.add(new Event(rs.getLong("sequence"),rs.getString("event_type"),rs.getString("payload_json"),rs.getLong("created_at")));}
            }
            // A cancelled run does not claim another run's successful ticket as its own result.
            if(r.status().equals("SUCCEEDED")) try(var p=prepare(c,"SELECT * FROM ticket WHERE operation_id=?")) {
                p.setString(1,t.operationId());try(var rs=p.executeQuery()){if(rs.next())ticket=new Ticket(rs.getString("id"),t.operationId(),rs.getString("title"),rs.getString("description"),rs.getLong("created_at"));}
            }
            return new Snapshot(r,t,attempts,events,ticket);
        });
    }
}
