package com.zxl.zxlaiagent.security;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
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
    else try(var s=SecurityDatabase.statement(c,"SELECT 1 FROM resource_owner WHERE resource_type=? AND resource_id=? AND tenant_id=? AND subject_id=? AND state='ACTIVE' FOR UPDATE",resource.type(),resource.id(),ref.tenantId(),ref.subjectId());var r=s.executeQuery()){if(!r.next())code="RESOURCE_UNAVAILABLE";}
   }
  }
  var decision=new AuthorizationDecision(UUID.randomUUID().toString(),version,"ALLOWED".equals(code),code);
  try(var s=SecurityDatabase.statement(c,"INSERT INTO authorization_audit(decision_id,run_id,phase,tenant_id,subject_id,session_id,action,resource_type,resource_id,version,allowed,code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",decision.decisionId(),runId,phase.name(),ref.tenantId(),ref.subjectId(),ref.sessionId(),action,resource==null?null:resource.type(),resource==null?null:resource.id(),version,decision.allowed(),decision.code())){s.executeUpdate();}
  return decision;
 }
 public <T>T gate(ActorRef ref,String action,ResourceRef resource,Phase phase,String runId,Supplier<T> start){record Result<T>(AuthorizationDecision decision,T value){}var result=db.tx(c->{var d=decide(c,ref,action,resource,phase,runId);return new Result<T>(d,d.allowed()?start.get():null);});result.decision().requireAllowed();return result.value();}
 /** Trusted administration primitive; caller authentication/administrative policy belongs at the entry boundary. */
 public void setMembershipActive(ActorRef ref,boolean active){db.tx(c->{AuthenticationService.lockEpoch(c,ref);try(var s=SecurityDatabase.statement(c,"UPDATE membership SET status=?,version=version+1 WHERE tenant_id=? AND subject_id=?",active?"ACTIVE":"REVOKED",ref.tenantId(),ref.subjectId())){s.executeUpdate();}try(var s=SecurityDatabase.statement(c,"UPDATE authorization_epoch SET version=version+1 WHERE tenant_id=? AND subject_id=?",ref.tenantId(),ref.subjectId())){s.executeUpdate();}return null;});}
 public void registerResource(ActorRef ref,ResourceRef resource){db.tx(c->{AuthenticationService.resolve(c,ref);try(var s=SecurityDatabase.statement(c,"INSERT INTO resource_owner(resource_type,resource_id,tenant_id,subject_id) VALUES (?,?,?,?)",resource.type(),resource.id(),ref.tenantId(),ref.subjectId())){s.executeUpdate();}return null;});}
 public void requireOwned(ActorRef ref,ResourceRef resource,Phase phase){decide(ref,resource.type().equals("conversation")?"conversation.readOwn":"run.readOwn",resource,phase,null).requireAllowed();}
 public List<String> ownedResourceIds(ActorRef ref,String type){return db.tx(c->{AuthenticationService.resolve(c,ref);var ids=new ArrayList<String>();try(var s=SecurityDatabase.statement(c,"SELECT resource_id FROM resource_owner WHERE resource_type=? AND tenant_id=? AND subject_id=? AND state='ACTIVE' ORDER BY resource_id",type,ref.tenantId(),ref.subjectId());var r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}return List.copyOf(ids);});}
}
