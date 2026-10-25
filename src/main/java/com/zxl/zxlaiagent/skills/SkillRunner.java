package com.zxl.zxlaiagent.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.research.ResearchRunner;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Fixed four-step business orchestration around the verified MCP research boundary. No ticket writes. */
public final class SkillRunner {
    public enum Status { NEEDS_INPUT,SUCCEEDED,NO_EVIDENCE,DENIED,FAILED,CANCELLED,TIMED_OUT,CAPACITY }
    public record Snapshot(String runId,SkillDefinition definition,String definitionSha256,String toolSnapshotSha256,String executorVersion,String policyVersion,long selectedAt) { }
    public record StepResult(String step,String status,long elapsedMs,String code) { }
    public record Draft(String schemaVersion,String title,String problem,String advice,String priority,boolean created,boolean requiresApproval) { }
    public record Result(Snapshot snapshot,Status status,String advice,Draft draft,List<StepResult> steps,String code) { public Result {steps=List.copyOf(steps);} }
    private final SkillRegistry registry;
    private final SkillExecutionPolicy policy;
    private final ResearchRunner.Gateway gateway;
    private final ResearchRunner.Model model;
    private final ResearchRunner.Verifier verifier;
    private final Semaphore admission=new Semaphore(2);
    public SkillRunner(SkillRegistry registry,SkillExecutionPolicy policy,ResearchRunner.Gateway gateway,ResearchRunner.Model model,ResearchRunner.Verifier verifier){
        this.registry=Objects.requireNonNull(registry);this.policy=Objects.requireNonNull(policy);this.gateway=Objects.requireNonNull(gateway);this.model=Objects.requireNonNull(model);this.verifier=Objects.requireNonNull(verifier);
    }
    public Result run(String id,String version,String question,Duration budget,SkillExecutionPolicy.Context context,BooleanSupplier cancelled,Consumer<AgentExecutionEvent> events){
        return run(id,version,question,budget,context,cancelled,events,new ResearchRunner.ModelGate(){public <T>T call(Supplier<T> action){return action.get();}});
    }
    public Result run(String id,String version,String question,Duration budget,SkillExecutionPolicy.Context context,BooleanSupplier cancelled,Consumer<AgentExecutionEvent> events,ResearchRunner.ModelGate modelGate){
        if(question==null||question.isBlank()||question.length()>4000||budget==null||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("SKILL_INVALID_REQUEST");
        var definition=registry.select(id,version);
        long started=System.nanoTime(),end=started+Math.min(budget.toNanos(),Duration.ofMillis(definition.budgetMs()).toNanos());
        var snapshot=new Snapshot(UUID.randomUUID().toString(),definition,definition.sha256(),SkillDefinition.digest(definition.tools()),"skill-executor-v1",policy.version(),System.currentTimeMillis());
        var trace=new Trace(events);trace.selected(snapshot);
        if(!admission.tryAcquire())return trace.finish(snapshot,Status.CAPACITY,"当前业务执行容量已满，请稍后重试。 / Skill capacity reached.",null,"SKILL_CAPACITY");
        try {
            check(end,cancelled);authorize(context,definition);
            trace.running("CLARIFY");
            // A bounded classroom minimum, not a claim to have completed semantic IT triage.
            boolean hasProduct=java.util.regex.Pattern.compile("(?i)vpn|sso|邮箱|email|mail|网络|network|客户端|client|登录|login|权限|permission|office|打印|printer").matcher(question).find();
            boolean hasErrorCode=java.util.regex.Pattern.compile("(?i)(?<![a-z0-9])[a-z]{0,8}[-_ ]?\\d{2,6}(?![a-z0-9])").matcher(question).find();
            if(!hasProduct||!hasErrorCode){
                trace.complete("CLARIFY","NEEDS_INPUT","SKILL_ERROR_CODE_REQUIRED");
                return trace.finish(snapshot,Status.NEEDS_INPUT,"请补充产品、错误码及现象，例如 VPN 401；尚未查询知识或创建工单。 / Provide product, error code and symptoms; no lookup or ticket was created.",null,"SKILL_ERROR_CODE_REQUIRED");
            }
            trace.complete("CLARIFY","SUCCEEDED","OK");trace.running("LOOKUP");
            AtomicBoolean noEvidence=new AtomicBoolean();
            var guardedGateway=new ResearchRunner.Gateway(){
                public List<ResearchRunner.Tool> discover(Duration remaining,BooleanSupplier cancel){
                    check(end,cancel);authorize(context,definition);
                    var discovered=gateway.discover(remaining,cancel);check(end,cancel);
                    if(discovered==null||discovered.size()!=1)throw new IllegalStateException("SKILL_TOOL_SNAPSHOT_CHANGED");
                    var candidate=discovered.get(0);var expected=definition.tools().get(0);
                    if(!expected.exposedName().equals(candidate.name())||!expected.schemaSha256().equals(SkillDefinition.digest(new McpSchema.Tool(candidate.name(),candidate.description(),candidate.schema()).inputSchema())))throw new IllegalStateException("SKILL_TOOL_SNAPSHOT_CHANGED");
                    // A discovery response is not authorization; filter before the model sees capabilities.
                    policy.authorize(context,definition,candidate.name(),true);return List.copyOf(discovered);
                }
                public String call(String name,String arguments,Duration remaining,BooleanSupplier cancel){
                    check(end,cancel);policy.authorize(context,definition,name,true);
                    String response=gateway.call(name,arguments,remaining,cancel);check(end,cancel);
                    JsonNode envelope=ToolResults.read(response);
                    noEvidence.set(envelope.path("success").asBoolean()&&envelope.path("data").path("hits").isArray()&&envelope.path("data").path("hits").isEmpty());return response;
                }
            };
            var guardedModel=new ResearchRunner.Model(){
                public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> tools,Duration remaining,BooleanSupplier cancel){check(end,cancel);authorize(context,definition);return model.select(q,tools,remaining,cancel);}
                public String answer(String q,List<JsonNode> evidence,Duration remaining,BooleanSupplier cancel){
                    check(end,cancel);authorize(context,definition);trace.complete("LOOKUP","SUCCEEDED","OK");trace.running("VERIFY_ADVISE");
                    return model.answer(q,evidence,remaining,cancel);
                }
            };
            String advice=new ResearchRunner(guardedGateway,guardedModel,verifier).run(question,remaining(end,cancelled),cancelled,trace::forward,modelGate);
            check(end,cancelled);authorize(context,definition);
            if(noEvidence.get()){
                trace.complete("LOOKUP","SUCCEEDED","OK");trace.running("VERIFY_ADVISE");trace.complete("VERIFY_ADVISE","FAILED","SKILL_NO_EVIDENCE");
                return trace.finish(snapshot,Status.NO_EVIDENCE,advice,null,"SKILL_NO_EVIDENCE");
            }
            trace.complete("VERIFY_ADVISE","SUCCEEDED","OK");trace.running("DRAFT");check(end,cancelled);authorize(context,definition);
            // Only Java creates this bounded schema. Advice already passed ResearchRunner citation/current-source checks.
            var draft=new Draft(definition.outputSchemaVersion(),question.substring(0,Math.min(100,question.length())),question,advice,
                    "ticket-draft-v2".equals(definition.outputSchemaVersion())?"NORMAL":null,false,true);
            if(advice.length()>10000||draft.created()||!draft.requiresApproval())throw new IllegalStateException("SKILL_INVALID_DRAFT");
            check(end,cancelled);authorize(context,definition);trace.complete("DRAFT","SUCCEEDED","OK");
            return trace.finish(snapshot,Status.SUCCEEDED,advice,draft,"OK");
        }catch(RuntimeException failure){
            Status status=classify(failure);String code=safeCode(failure,status);
            trace.failCurrent(status==Status.CANCELLED?"CANCELLED":"FAILED",code);
            return trace.finish(snapshot,status,message(status),null,code);
        }finally{admission.release();}
    }
    private void authorize(SkillExecutionPolicy.Context context,SkillDefinition definition){policy.authorize(context,definition,definition.tools().get(0).exposedName(),true);}
    private static Duration remaining(long end,BooleanSupplier cancel){check(end,cancel);return Duration.ofNanos(Math.max(1,end-System.nanoTime()));}
    private static void check(long end,BooleanSupplier cancel){if(Thread.currentThread().isInterrupted()||cancel.getAsBoolean())throw new CancellationException("SKILL_CANCELLED");if(System.nanoTime()>=end)throw new IllegalStateException("SKILL_DEADLINE");}
    private static Status classify(RuntimeException e){
        String code=e.getMessage()==null?"":e.getMessage();
        if(e instanceof CancellationException||code.contains("CANCELLED"))return Status.CANCELLED;
        if(e instanceof SecurityException||code.contains("ALLOWLIST")||code.contains("SELECTION_DENIED")||code.contains("NOT_AUTHORIZED"))return Status.DENIED;
        if(code.contains("DEADLINE"))return Status.TIMED_OUT;if(code.contains("CAPACITY"))return Status.CAPACITY;return Status.FAILED;
    }
    private static String safeCode(RuntimeException e,Status status){
        if(status==Status.DENIED)return "SKILL_POLICY_DENIED";if(status==Status.CANCELLED)return "SKILL_CANCELLED";if(status==Status.TIMED_OUT)return "SKILL_DEADLINE";if(status==Status.CAPACITY)return "SKILL_CAPACITY";
        String message=e.getMessage();
        return message!=null&&Set.of("SKILL_TOOL_SNAPSHOT_CHANGED","SKILL_TOOL_UNAVAILABLE").contains(message)?message:"SKILL_EXECUTION_FAILED";
    }
    private static String message(Status status){return switch(status){case DENIED->"服务端策略拒绝该动作，未创建工单。 / Server policy denied the action; no ticket created.";case CANCELLED->"本地执行已取消；不代表远程回滚，未创建工单。 / Local execution cancelled; remote rollback is not implied.";case TIMED_OUT->"统一执行期限已到，未发布草稿。 / Shared deadline reached; no draft published.";case CAPACITY->"执行容量已满，请稍后重试。 / Capacity reached.";default->"工具或证据校验未完成，未创建工单；请检查服务状态。 / Tool or evidence verification failed; no ticket created.";};}
    private static final class Trace {
        final Consumer<AgentExecutionEvent> events;final LinkedHashMap<String,StepResult> steps=new LinkedHashMap<>();boolean terminal;String current;long currentStart;
        Trace(Consumer<AgentExecutionEvent> events){this.events=Objects.requireNonNull(events);}
        synchronized void selected(Snapshot snapshot){forward(AgentExecutionEvent.of("SKILL_SELECTED",0,ToolResults.json(snapshot),"running"));}
        synchronized void running(String step){if(terminal)return;current=step;currentStart=System.nanoTime();forward(AgentExecutionEvent.of("SKILL_STEP",steps.size()+1,ToolResults.json(new StepResult(step,"RUNNING",0,"OK")),"running"));}
        synchronized void complete(String step,String status,String code){if(terminal||steps.containsKey(step))return;var result=new StepResult(step,status,Math.max(0,(System.nanoTime()-currentStart)/1_000_000),code);steps.put(step,result);forward(AgentExecutionEvent.of("SKILL_STEP",steps.size(),ToolResults.json(result),status.toLowerCase(Locale.ROOT)));current=null;}
        synchronized void failCurrent(String status,String code){if(current!=null)complete(current,status,code);}
        synchronized void forward(AgentExecutionEvent event){if(!terminal)events.accept(event);}
        synchronized Result finish(Snapshot snapshot,Status status,String advice,Draft draft,String code){var result=new Result(snapshot,status,advice,draft,List.copyOf(steps.values()),code);if(!terminal){forward(AgentExecutionEvent.of("SKILL_TERMINAL",steps.size(),ToolResults.json(result),status.name().toLowerCase(Locale.ROOT)));terminal=true;}return result;}
    }
}
