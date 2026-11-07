package com.zxl.zxlaiagent.security;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
/** PG epoch row is the shared linearization gate for decisions, revocations and action starts. */
public final class AuthorizationService {
 private final SecurityDatabase db;
 private static final Set<String> ACTIONS=Set.of("knowledge.read","knowledge.manage","conversation.readOwn","conversation.writeOwn","run.readOwn","run.writeOwn","skill.execute","agent.execute");
 public AuthorizationService(SecurityDatabase db){this.db=db;}
 public AuthorizationDecision decide(ActorRef ref,String action,ResourceRef resource,Phase phase,String runId){return db.tx(c->decide(c,ref,action,resource,phase,runId));}
 AuthorizationDecision decide(Connection c,ActorRef ref,String action,ResourceRef resource,Phase phase,String runId)throws SQLException{
  AuthenticationService.validateRef(ref);if(action==null||action.length()>80||phase==null||(resource!=null&&(!AuthenticationService.validId(resource.type())||resource.type().length()>80||!AuthenticationService.validId(resource.id()))))throw new SecurityFailure("ACTION_DENIED",403);
  try{AuthenticationService.lockEpoch(c,ref);}catch(SecurityFailure failure){if(!"UNAUTHENTICATED".equals(failure.code()))throw failure;}
  String version="unresolved";String code="UNAUTHENTICATED";boolean authenticated=false;String role="";
  try(var s=SecurityDatabase.statement(c,"SELECT e.version,s.version,m.version,m.role,(s.revoked_at IS NULL AND s.version=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND t.status='ACTIVE') FROM authorization_epoch e JOIN membership m USING(tenant_id,subject_id) JOIN auth_session s USING(tenant_id,subject_id) JOIN subject u USING(subject_id) JOIN tenant t USING(tenant_id) WHERE e.tenant_id=? AND e.subject_id=? AND s.session_id=? FOR UPDATE OF e,s,m",ref.authenticationVersion(),ref.tenantId(),ref.subjectId(),ref.sessionId());var r=s.executeQuery()){if(r.next()){version="epoch:"+r.getLong(1)+";session:"+r.getLong(2)+";member:"+r.getLong(3)+";policy:1";role=r.getString(4);authenticated=r.getBoolean(5);}}
  if(authenticated)authenticated=AuthenticationService.sessionNotExpired(c,ref);
  if(authenticated){code=!ACTIONS.contains(action)?"ACTION_DENIED":"ALLOWED";if("knowledge.manage".equals(action)&&!"knowledge-maintainer".equals(role))code="ACTION_DENIED";
   if("ALLOWED".equals(code)&&action.endsWith("Own")){
    String expected=action.startsWith("conversation.")?"conversation":"run";
    if(resource==null||!expected.equals(resource.type()))code="RESOURCE_UNAVAILABLE";
    else try(var s=SecurityDatabase.statement(c,"SELECT legacy_import_id FROM resource_owner WHERE resource_type=? AND resource_id=? AND tenant_id=? AND subject_id=? AND state='ACTIVE' FOR UPDATE",resource.type(),resource.id(),ref.tenantId(),ref.subjectId());var r=s.executeQuery()){
     if(!r.next())code="RESOURCE_UNAVAILABLE";
     else if(action.endsWith("writeOwn")&&r.getString(1)!=null)code="ARCHIVE_READ_ONLY";
    }
   }
  }
  var decision=new AuthorizationDecision(UUID.randomUUID().toString(),version,"ALLOWED".equals(code),code);
  try(var s=SecurityDatabase.statement(c,"INSERT INTO authorization_audit(decision_id,run_id,phase,tenant_id,subject_id,session_id,action,resource_type,resource_id,version,allowed,code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",decision.decisionId(),runId,phase.name(),ref.tenantId(),ref.subjectId(),ref.sessionId(),action,resource==null?null:resource.type(),resource==null?null:resource.id(),version,decision.allowed(),decision.code())){s.executeUpdate();}
  return decision;
 }
 /** The verified archive digest is private to the active owner. Normal conversations have no receipt. */
 public String legacyConversationDigest(ActorRef ref, UUID id) {
  Objects.requireNonNull(id);
  record Checked(AuthorizationDecision decision,String digest,boolean missingReceipt){}
  var checked=db.tx(c->{
   var decision=decide(c,ref,"conversation.readOwn",new ResourceRef("conversation",id.toString()),Phase.HISTORY_READ,null);
   if(!decision.allowed())return new Checked(decision,null,false);
   try(var s=SecurityDatabase.statement(c,"""
      SELECT o.legacy_import_id, e.sha256
      FROM resource_owner o
      LEFT JOIN legacy_import_receipt r ON r.import_id=o.legacy_import_id AND r.tenant_id=o.tenant_id AND r.subject_id=o.subject_id
      LEFT JOIN legacy_import_entry e ON e.import_id=r.import_id AND e.conversation_id=o.resource_id
      WHERE o.resource_type='conversation' AND o.resource_id=? AND o.tenant_id=? AND o.subject_id=? AND o.state='ACTIVE'
      """,id.toString(),ref.tenantId(),ref.subjectId());var rows=s.executeQuery()){
    if(!rows.next())return new Checked(decision,null,true);
    String archive=rows.getString(1);
    String digest=rows.getString(2);
    return new Checked(decision,digest,archive!=null&&digest==null);
   }
  });
  checked.decision().requireAllowed();
  if(checked.missingReceipt())throw new SecurityFailure("ARCHIVE_RECEIPT_UNAVAILABLE",503);
  return checked.digest();
 }
 public <T>T gate(ActorRef ref,String action,ResourceRef resource,Phase phase,String runId,Supplier<T> start){return gateAll(ref,List.of(new Permission(action,resource)),phase,runId,start);}
 /** All requirements share one epoch transaction. The callback only starts/enqueues an operation. */
 public record Permission(String action, ResourceRef resource) {}

 /**
  * A short local SQL gate on the caller-owned transaction, never around remote waits.
  * Decisions precede a business savepoint: failures roll back business writes while
  * retaining their audit, and epoch locks live until the caller commits.
  */
 public <T> OperationBoundary.SqlOutcome<T> sqlGate(Connection connection, ActorRef ref,
   List<Permission> requirements, Phase phase, String runId, OperationBoundary.SqlWork<T> work)throws SQLException {
  if(connection==null||connection.getAutoCommit()||requirements==null||requirements.isEmpty()
    ||requirements.size()>8||requirements.stream().anyMatch(Objects::isNull)||work==null)
   throw new SecurityFailure("ACTION_DENIED",403);
  db.requireSameDatabase(connection);
  String searchPath;
  try(var s=connection.createStatement();var r=s.executeQuery("SHOW search_path")){r.next();searchPath=r.getString(1);}
  var decisions=new ArrayList<AuthorizationDecision>();
  try {
   setSearchPath(connection,"lesson17,pg_catalog");
   for(var permission:List.copyOf(requirements))
    decisions.add(decide(connection,ref,permission.action(),permission.resource(),phase,runId));
  } finally { setSearchPath(connection,searchPath); }
  for(var decision:decisions)if(!decision.allowed()){
   try{decision.requireAllowed();}catch(SecurityFailure denied){return new OperationBoundary.SqlOutcome<>(null,denied);}
  }
  Savepoint business=connection.setSavepoint();
  try {
   T value=work.run(connection);connection.releaseSavepoint(business);
   return new OperationBoundary.SqlOutcome<>(value,null);
  } catch(SQLException|RuntimeException failure) {
   connection.rollback(business);connection.releaseSavepoint(business);
   RuntimeException deferred=failure instanceof RuntimeException runtime?runtime:
     new IllegalStateException("PostgreSQL authorized business transaction failed",failure);
   return new OperationBoundary.SqlOutcome<>(null,deferred);
  }
 }
 private static void setSearchPath(Connection connection,String searchPath)throws SQLException {
  try(var s=connection.prepareStatement("SELECT set_config('search_path',?,true)")){s.setString(1,searchPath);s.execute();}
 }

 public <T> T gateAll(ActorRef ref, List<Permission> requirements, Phase phase, String runId, Supplier<T> start) {
  if (requirements == null || requirements.isEmpty() || requirements.size() > 8 || start == null
      || requirements.stream().anyMatch(Objects::isNull)) throw new SecurityFailure("ACTION_DENIED", 403);
  var permissions = List.copyOf(requirements);
  record Checked<T>(List<AuthorizationDecision> decisions, T value, RuntimeException startFailure) {}
  var checked = db.tx(connection -> {
   var decisions = new ArrayList<AuthorizationDecision>();
   for (var permission : permissions)
    decisions.add(decide(connection, ref, permission.action(), permission.resource(), phase, runId));
   boolean allowed = decisions.stream().allMatch(AuthorizationDecision::allowed);
   if (!allowed) return new Checked<T>(List.copyOf(decisions), null, null);
   try { return new Checked<T>(List.copyOf(decisions), start.get(), null); }
   catch (RuntimeException failure) { return new Checked<T>(List.copyOf(decisions), null, failure); }
  });
  // Denied Decisions must commit too; throwing within the transaction would erase their audit.
  checked.decisions().forEach(AuthorizationDecision::requireAllowed);
  if (checked.startFailure() != null) throw checked.startFailure();
  return checked.value();
 }
 /** Trusted administration primitive; caller authentication/administrative policy belongs at the entry boundary. */
 public void setMembershipActive(ActorRef ref,boolean active){db.tx(c->{AuthenticationService.lockEpoch(c,ref);try(var s=SecurityDatabase.statement(c,"UPDATE membership SET status=?,version=version+1 WHERE tenant_id=? AND subject_id=?",active?"ACTIVE":"REVOKED",ref.tenantId(),ref.subjectId())){s.executeUpdate();}try(var s=SecurityDatabase.statement(c,"UPDATE authorization_epoch SET version=version+1 WHERE tenant_id=? AND subject_id=?",ref.tenantId(),ref.subjectId())){s.executeUpdate();}return null;});}
 public void registerResource(ActorRef ref,ResourceRef resource){db.tx(c->{AuthenticationService.resolve(c,ref);try(var s=SecurityDatabase.statement(c,"INSERT INTO resource_owner(resource_type,resource_id,tenant_id,subject_id) VALUES (?,?,?,?)",resource.type(),resource.id(),ref.tenantId(),ref.subjectId())){s.executeUpdate();}return null;});}
 /** Register a server-created run with current admission Decisions on this same transaction. */
 public void registerResourceAuthorized(ActorRef ref,ResourceRef resource,List<Permission> permissions,String runId){
  if(resource==null||!"run".equals(resource.type())||!Objects.equals(resource.id(),runId)||!AuthenticationService.validId(runId))throw new IllegalArgumentException("Server run resource required");
  db.tx(c->sqlGate(c,ref,permissions,Phase.ENTRY,runId,connection->{
   try(var s=SecurityDatabase.statement(connection,"INSERT INTO resource_owner(resource_type,resource_id,tenant_id,subject_id) VALUES (?,?,?,?)",resource.type(),resource.id(),ref.tenantId(),ref.subjectId())){s.executeUpdate();}
   return null;
  })).result();
 }
 /** Server-only compensation after failed pre-dispatch registration, including a revoked submitting session. */
 public void discardUnstartedRun(ActorRef ref,String runId){
  Objects.requireNonNull(ref);if(!AuthenticationService.validId(runId)||!AuthenticationService.validId(ref.subjectId())||!AuthenticationService.validId(ref.tenantId()))throw new IllegalArgumentException("Exact run owner required");
  db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM resource_owner WHERE resource_type='run' AND resource_id=? AND tenant_id=? AND subject_id=?",runId,ref.tenantId(),ref.subjectId())){s.executeUpdate();}return null;});
 }
 public void requireOwned(ActorRef ref,ResourceRef resource,Phase phase){decide(ref,resource.type().equals("conversation")?"conversation.readOwn":"run.readOwn",resource,phase,null).requireAllowed();}
 public List<String> ownedResourceIds(ActorRef ref,String type){return db.tx(c->{AuthenticationService.resolve(c,ref);var ids=new ArrayList<String>();try(var s=SecurityDatabase.statement(c,"SELECT resource_id FROM resource_owner WHERE resource_type=? AND tenant_id=? AND subject_id=? AND state='ACTIVE' ORDER BY resource_id",type,ref.tenantId(),ref.subjectId());var r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}return List.copyOf(ids);});}
 /** Query only bounded retained candidates, never load every historical owner for a status request. */
 public List<String> ownedResourceIds(ActorRef ref,String type,Collection<String> candidates){
  if(candidates==null||candidates.size()>200||candidates.stream().anyMatch(id->!AuthenticationService.validId(id)))throw new IllegalArgumentException("At most 200 valid retained candidates required");
  var selected=List.copyOf(new LinkedHashSet<>(candidates));
  return db.tx(c->{
   AuthenticationService.resolve(c,ref);if(selected.isEmpty())return List.of();
   var array=c.createArrayOf("varchar",selected.toArray(String[]::new));
   try(var statement=SecurityDatabase.statement(c,"SELECT resource_id FROM resource_owner WHERE resource_type=? AND tenant_id=? AND subject_id=? AND state='ACTIVE' AND resource_id=ANY(?) ORDER BY resource_id",type,ref.tenantId(),ref.subjectId())){
    statement.setArray(4,array);var ids=new ArrayList<String>();try(var rows=statement.executeQuery()){while(rows.next())ids.add(rows.getString(1));}return List.copyOf(ids);
   }finally{array.free();}
  });
 }
}
