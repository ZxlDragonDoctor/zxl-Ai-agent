package com.zxl.zxlaiagent.multiagent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.ActorBoundary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Identity comes only from the server request boundary; model/business inputs never choose it. */
@Service
@ConditionalOnProperty(name="agent.security.mode",havingValue="required")
public final class ScopedMultiAgentService {
    private final OrchestratorAgent orchestrator;private final AuthorizationService authorization;
    private final SecurityDatabase database;
    private final DurableRunStore journal = new DurableRunStore();
    private final ObjectMapper json = new ObjectMapper();

    public ScopedMultiAgentService(OrchestratorAgent orchestrator, AuthorizationService authorization,
                                  SecurityDatabase database) {
        this.orchestrator = orchestrator;
        this.authorization = authorization;
        this.database = database;
    }
    private static List<AuthorizationService.Permission> admission(){return List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("knowledge.read",null));}
    private static List<AuthorizationService.Permission> readPermissions(String id){return List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("knowledge.read",null),new AuthorizationService.Permission("run.readOwn",new ResourceRef("run",id)));}
    public String submit(ActorRef actor,String goal,Map<String,Object> context,Long timeoutMs){
        requireActor(actor);var created=new AtomicReference<String>();
        try{
            return orchestrator.submitScoped(goal,context==null?Map.of():context,timeoutMs,id->{
                authorization.registerResourceAuthorized(actor,new ResourceRef("run",id),admission(),id);created.set(id);
                var boundary = new ActorBoundary(authorization, actor, readPermissions(id), id);
                return new OrchestratorAgent.ScopedSubmission(boundary,
                        new TrustedRunContext(actor, id, new PrivateRunEvents(), reportTransaction(actor, id, boundary)));
            }).executionId();
        }catch(RuntimeException failure){
            if(created.get()!=null){try{authorization.discardUnstartedRun(actor,created.get());}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}}
            throw failure;
        }
    }
    private WorkflowRun.ReportTransaction reportTransaction(ActorRef actor, String id, ActorBoundary boundary) {
        return new WorkflowRun.ReportTransaction() {
            @Override public <T> T commit(Supplier<T> mutation, Supplier<WorkflowRun.Report> snapshot) {
                return database.tx(connection -> boundary.sql(connection, Phase.RESULT_PUBLISH, sql -> {
                    T result = mutation.get();
                    var report = snapshot.get();
                    var event = new PublicRunEvent(report.sequence(), report.status(), report.startedAt(),
                            report.finishedAt(), report.tasks().size(), report.result(),
                            report.error() == null ? null : report.error().code());
                    journal.append(sql, actor.tenantId(), id, event.sequence(), event.status(), encode(event));
                    return result;
                })).result();
            }
        };
    }

    public record PublicRunEvent(long sequence, String status, long startedAt, long finishedAt,
                                 int taskCount, String result, String errorCode) {}
    public record EventPage(long lastSequence, List<PublicRunEvent> events) {}

    public EventPage eventsAfter(ActorRef actor, String id, long cursor) {
        requireActor(actor);
        requireId(id);
        if (cursor < 0) throw new SecurityFailure("INVALID_EVENT_CURSOR", 400);
        return hidden(() -> database.tx(connection -> authorization.sqlGate(connection, actor, readPermissions(id),
                Phase.HISTORY_READ, id, sql -> {
                    var snapshot = journal.get(sql, actor.tenantId(), id)
                            .orElseThrow(() -> new SecurityFailure("RUN_HISTORY_UNAVAILABLE", 404));
                    if (cursor > snapshot.sequence()) throw new SecurityFailure("EVENT_CURSOR_AHEAD", 409);
                    var events = journal.eventsAfter(sql, actor.tenantId(), id, cursor, 50).stream()
                            .takeWhile(event -> event.sequence() <= snapshot.sequence())
                            .map(event -> decode(event.json())).toList();
                    return new EventPage(snapshot.sequence(), events);
                })).result());
    }

    private String encode(PublicRunEvent event) {
        try { return json.writeValueAsString(event); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Run event encoding failed", failure); }
    }

    private PublicRunEvent decode(String payload) {
        try { return json.readValue(payload, PublicRunEvent.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Run event decoding failed", failure); }
    }
    public OrchestratorAgent.ScopedRunView detail(ActorRef actor,String id){
        requireActor(actor);requireId(id);
        return hidden(()->{
            authorization.requireOwned(actor,new ResourceRef("run",id),Phase.HISTORY_READ);
            return authorization.gateAll(actor,readPermissions(id),Phase.RESULT_PUBLISH,id,()->{
                var view=orchestrator.runStatusScoped(id);if(view==null)throw new SecurityFailure("NOT_FOUND",404);return view;
            });
        });
    }
    public record CancelAcknowledgement(String executionId,String status){}
    public CancelAcknowledgement cancel(ActorRef actor,String id){
        requireActor(actor);requireId(id);
        return hidden(()->{
            authorization.decide(actor,"run.writeOwn",new ResourceRef("run",id),Phase.ENTRY,id).requireAllowed();
            var write=new ActorBoundary(authorization,actor,List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("run.writeOwn",new ResourceRef("run",id))),id);
            var receipt=orchestrator.cancelScoped(id,write);
            if(receipt.status().equals("not_found"))throw new SecurityFailure("NOT_FOUND",404);
            // A write permission never grants disclosure of the report embedded in the internal receipt.
            return new CancelAcknowledgement(id,receipt.status());
        });
    }
    public record WorkspaceRuns(List<OrchestratorAgent.ScopedRunView> runs){public WorkspaceRuns{runs=List.copyOf(runs);}}
    public WorkspaceRuns status(ActorRef actor){
        requireActor(actor);var runs=new ArrayList<OrchestratorAgent.ScopedRunView>();
        for(String id:authorization.ownedResourceIds(actor,"run",orchestrator.retainedScopedRunIds(200))){
            var view=hidden(()->authorization.gateAll(actor,readPermissions(id),Phase.RESULT_PUBLISH,id,()->orchestrator.runStatusScoped(id)));
            if(view!=null)runs.add(view);
        }
        return new WorkspaceRuns(runs);
    }
    private static void requireActor(ActorRef actor){if(actor==null)throw new SecurityFailure("UNAUTHENTICATED",401);}
    private static void requireId(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new SecurityFailure("NOT_FOUND",404);}}
    private static <T>T hidden(Supplier<T> action){try{return action.get();}catch(SecurityFailure denied){if("RESOURCE_UNAVAILABLE".equals(denied.code()))throw new SecurityFailure("NOT_FOUND",404);throw denied;}}
}
