package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.research.ScopedResearchFactory;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.*;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Reviewed four-step formal skill. Trusted ActorRef never becomes model/tool arguments.
 * Publishers must be bound BaseAgent publishers that authorize actual queue admission and delivery.
 * Checks here do not wrap that publisher inside another PostgreSQL gate.
 */
public final class ScopedSkillRunner {
    private final SkillRegistry registry;
    private final ScopedResearchFactory research;
    private final AuthorizationService authorization;
    private final Semaphore admission=new Semaphore(2);
    public ScopedSkillRunner(SkillRegistry registry,ScopedResearchFactory research,AuthorizationService authorization){
        this.registry=Objects.requireNonNull(registry);this.research=Objects.requireNonNull(research);this.authorization=Objects.requireNonNull(authorization);
    }
    public SkillRunner.Result runWithAuthorizedPublisher(ActorRef actor,String conversationId,String runId,String id,String version,String question,
            Duration budget,BooleanSupplier cancelled,Consumer<AgentExecutionEvent> authorizedPublisher){
        if(actor==null)throw new SecurityFailure("UNAUTHENTICATED",401);
        if(conversationId==null||conversationId.isBlank()||runId==null||runId.isBlank())throw new SecurityFailure("SCOPED_EXECUTION_CONTEXT_REQUIRED",403);
        Objects.requireNonNull(cancelled);Objects.requireNonNull(authorizedPublisher);
        if(question==null||question.isBlank()||question.length()>4000||budget==null||budget.isZero()||budget.isNegative()||budget.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("SKILL_INVALID_REQUEST");
        var definition=registry.select(id,version);
        if(!FormalSkillController.formal(definition))throw new IllegalArgumentException("SKILL_UNREVIEWED_FORMAL_VERSION");
        long end=System.nanoTime()+Math.min(budget.toNanos(),Duration.ofMillis(definition.budgetMs()).toNanos());
        var additional=List.of(new AuthorizationService.Permission("skill.execute",null),
                new AuthorizationService.Permission("conversation.readOwn",new ResourceRef("conversation",conversationId)),
                new AuthorizationService.Permission("run.readOwn",new ResourceRef("run",runId)));
        var permissions=new ArrayList<>(additional);permissions.add(new AuthorizationService.Permission("agent.execute",null));permissions.add(new AuthorizationService.Permission("knowledge.read",null));
        OperationBoundary boundary=new ActorBoundary(authorization,actor,permissions,runId);
        check(end,cancelled);boundary.check(Phase.ENTRY);
        // This label describes the decision authority, never a fabricated current decision version.
        var snapshot=new SkillRunner.Snapshot(runId,definition,definition.sha256(),SkillDefinition.digest(definition.tools()),
                "authenticated-skill-executor-v1","pg-current-per-phase; decisions in authorization_audit",System.currentTimeMillis());
        var trace=new Trace(boundary,authorizedPublisher,end,cancelled);
        trace.event(AgentExecutionEvent.of("SKILL_SELECTED",0,ToolResults.json(snapshot),"running"));
        if(!admission.tryAcquire())return trace.finish(snapshot,SkillRunner.Status.CAPACITY,"当前执行容量已满，请稍后重试。 / Skill capacity reached.",null,"SKILL_CAPACITY");
        try{
            boundary.check(Phase.DEQUEUE);trace.running("CLARIFY");
            boolean product=java.util.regex.Pattern.compile("(?i)vpn|sso|邮箱|email|mail|网络|network|客户端|client|登录|login|权限|permission|office|打印|printer").matcher(question).find();
            boolean code=java.util.regex.Pattern.compile("(?i)(?<![a-z0-9])[a-z]{0,8}[-_ ]?\\d{2,6}(?![a-z0-9])").matcher(question).find();
            if(!product||!code){
                trace.complete("CLARIFY","NEEDS_INPUT","SKILL_ERROR_CODE_REQUIRED");
                return trace.finish(snapshot,SkillRunner.Status.NEEDS_INPUT,"请补充产品、错误码及现象，例如 VPN 401；尚未查询知识或创建工单。 / Provide product, error code and symptoms; no lookup or ticket created.",null,"SKILL_ERROR_CODE_REQUIRED");
            }
            trace.complete("CLARIFY","SUCCEEDED","OK");trace.running("LOOKUP");check(end,cancelled);boundary.check(Phase.DISCOVERY);
            var runner=research.create(actor,runId,additional);
            String advice=runner.runWithAuthorizedPublisher(question,remaining(end,cancelled),cancelled,trace::researchEvent);
            check(end,cancelled);boundary.check(Phase.RESULT_PUBLISH);
            if(!trace.steps.containsKey("LOOKUP"))trace.complete("LOOKUP","SUCCEEDED","OK");
            if(!"VERIFY_ADVISE".equals(trace.current))trace.running("VERIFY_ADVISE");
            if(trace.noEvidence){
                trace.complete("VERIFY_ADVISE","FAILED","SKILL_NO_EVIDENCE");
                return trace.finish(snapshot,SkillRunner.Status.NO_EVIDENCE,advice,null,"SKILL_NO_EVIDENCE");
            }
            trace.complete("VERIFY_ADVISE","SUCCEEDED","OK");trace.running("DRAFT");check(end,cancelled);boundary.check(Phase.RESULT_PUBLISH);
            if(advice==null||advice.isBlank()||advice.length()>10000)throw new IllegalStateException("SKILL_INVALID_DRAFT");
            var draft=new SkillRunner.Draft(definition.outputSchemaVersion(),question.substring(0,Math.min(100,question.length())),question,advice,
                    "ticket-draft-v2".equals(definition.outputSchemaVersion())?"NORMAL":null,false,true);
            trace.complete("DRAFT","SUCCEEDED","OK");
            return trace.finish(snapshot,SkillRunner.Status.SUCCEEDED,advice,draft,"OK");
        }catch(SecurityFailure denied){throw denied;}
        catch(RuntimeException failure){
            var status=classify(failure);String code=switch(status){case CANCELLED->"SKILL_CANCELLED";case TIMED_OUT->"SKILL_DEADLINE";case CAPACITY->"SKILL_CAPACITY";default->"SKILL_EXECUTION_FAILED";};
            return trace.failNeutral(snapshot,status,code);
        }finally{admission.release();}
    }
    private static SkillRunner.Status classify(RuntimeException failure){
        if(failure instanceof CancellationException)return SkillRunner.Status.CANCELLED;
        String code=failure.getMessage();
        if(failure instanceof com.zxl.zxlaiagent.knowledge.mcp.KnowledgeMcpClient.Failure typed)code=typed.code();
        if(failure instanceof com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpClient.Failure typed)code=typed.code();
        if("MCP_CANCELLED".equals(code))return SkillRunner.Status.CANCELLED;
        if(Set.of("SKILL_DEADLINE","RESEARCH_DEADLINE","MCP_DEADLINE_EXCEEDED").contains(code==null?"":code))return SkillRunner.Status.TIMED_OUT;
        if(Set.of("RESEARCH_CAPACITY","MCP_CAPACITY").contains(code==null?"":code))return SkillRunner.Status.CAPACITY;
        return SkillRunner.Status.FAILED;
    }
    private static void check(long end,BooleanSupplier cancelled){if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new CancellationException("SKILL_CANCELLED");if(System.nanoTime()>=end)throw new IllegalStateException("SKILL_DEADLINE");}
    private static Duration remaining(long end,BooleanSupplier cancelled){check(end,cancelled);return Duration.ofNanos(Math.max(1,end-System.nanoTime()));}
    private static final class Trace {
        private final OperationBoundary boundary;private final Consumer<AgentExecutionEvent> publisher;
        private final long end;private final BooleanSupplier cancelled;
        private final LinkedHashMap<String,SkillRunner.StepResult> steps=new LinkedHashMap<>();
        private String current;private long start;private boolean noEvidence;
        Trace(OperationBoundary boundary,Consumer<AgentExecutionEvent> publisher,long end,BooleanSupplier cancelled){this.boundary=boundary;this.publisher=publisher;this.end=end;this.cancelled=cancelled;}
        void event(AgentExecutionEvent event){check(end,cancelled);boundary.check(Phase.RESULT_PUBLISH);publisher.accept(event);}
        void running(String step){check(end,cancelled);boundary.check(Phase.DEQUEUE);current=step;start=System.nanoTime();event(AgentExecutionEvent.of("SKILL_STEP",steps.size()+1,ToolResults.json(new SkillRunner.StepResult(step,"RUNNING",0,"OK")),"running"));}
        void complete(String step,String status,String code){if(steps.containsKey(step))return;var result=new SkillRunner.StepResult(step,status,Math.max(0,(System.nanoTime()-start)/1_000_000),code);steps.put(step,result);current=null;event(AgentExecutionEvent.of("SKILL_STEP",steps.size(),ToolResults.json(result),status.toLowerCase(Locale.ROOT)));}
        void researchEvent(AgentExecutionEvent event){
            event(event);
            if("TOOL_COMPLETED".equals(event.type())){
                try{var result=ToolResults.read(event.content());var hits=result.path("data").path("hits");noEvidence=result.path("success").asBoolean()&&hits.isArray()&&hits.isEmpty();}
                catch(RuntimeException previewTruncated){/* ResearchRunner validates the complete response itself. */}
                complete("LOOKUP","SUCCEEDED","OK");running("VERIFY_ADVISE");
            }
        }
        SkillRunner.Result failNeutral(SkillRunner.Snapshot snapshot,SkillRunner.Status status,String code){
            // Local terminal bookkeeping is independent of the already-exhausted business budget.
            // This path accepts only Java-created neutral fields, never advice, draft or tool content.
            if(current!=null){
                String stepStatus=status==SkillRunner.Status.CANCELLED?"CANCELLED":"FAILED";
                var failed=new SkillRunner.StepResult(current,stepStatus,Math.max(0,(System.nanoTime()-start)/1_000_000),code);
                steps.put(current,failed);current=null;
                neutralEvent(AgentExecutionEvent.of("SKILL_STEP",steps.size(),ToolResults.json(failed),stepStatus.toLowerCase(Locale.ROOT)));
            }
            var result=new SkillRunner.Result(snapshot,status,
                    "执行未完成，未发布工单草稿，也未创建工单。 / Execution incomplete; no ticket draft published or ticket created.",
                    null,List.copyOf(steps.values()),code);
            neutralEvent(AgentExecutionEvent.of("SKILL_TERMINAL",steps.size(),ToolResults.json(result),status.name().toLowerCase(Locale.ROOT)));
            return result;
        }
        private void neutralEvent(AgentExecutionEvent event){
            // Recheck current authority, then let BaseAgent gate actual queue admission/delivery.
            // A closed socket can discard these neutral frames; it cannot erase the local outcome.
            boundary.check(Phase.RESULT_PUBLISH);
            try{publisher.accept(event);}catch(CancellationException closed){/* No remote delivery is claimed. */}
        }
        SkillRunner.Result finish(SkillRunner.Snapshot snapshot,SkillRunner.Status status,String advice,SkillRunner.Draft draft,String code){
            var result=new SkillRunner.Result(snapshot,status,advice,draft,List.copyOf(steps.values()),code);
            event(AgentExecutionEvent.of("SKILL_TERMINAL",steps.size(),ToolResults.json(result),status.name().toLowerCase(Locale.ROOT)));return result;
        }
    }
}
