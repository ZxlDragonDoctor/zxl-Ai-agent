package com.zxl.zxlaiagent.multiagent.security;

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
    public ScopedMultiAgentService(OrchestratorAgent orchestrator,AuthorizationService authorization){this.orchestrator=orchestrator;this.authorization=authorization;}
    private static List<AuthorizationService.Permission> admission(){return List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("knowledge.read",null));}
    private static List<AuthorizationService.Permission> readPermissions(String id){return List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("knowledge.read",null),new AuthorizationService.Permission("run.readOwn",new ResourceRef("run",id)));}
    public String submit(ActorRef actor,String goal,Map<String,Object> context,Long timeoutMs){
        requireActor(actor);var created=new AtomicReference<String>();
        try{
            return orchestrator.submitScoped(goal,context==null?Map.of():context,timeoutMs,id->{
                authorization.registerResourceAuthorized(actor,new ResourceRef("run",id),admission(),id);created.set(id);
                return new OrchestratorAgent.ScopedSubmission(new ActorBoundary(authorization,actor,readPermissions(id),id),new TrustedRunContext(actor,id,new PrivateRunEvents()));
            }).executionId();
        }catch(RuntimeException failure){
            if(created.get()!=null){try{authorization.discardUnstartedRun(actor,created.get());}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}}
            throw failure;
        }
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
