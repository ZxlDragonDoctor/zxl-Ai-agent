package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpProtocol;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One explicit model selection, one allowed read, then grounded synthesis. No nested agent retry loop. */
public final class ResearchRunner {
    public record Tool(String name,String description,String schema) { }
    public record Selection(String callId,String name,String arguments) { }
    public interface Gateway {
        List<Tool> discover(Duration remaining,BooleanSupplier cancelled);
        String call(String name,String arguments,Duration remaining,BooleanSupplier cancelled);
    }
    public interface Model {
        Selection select(String question,List<Tool> tools,Duration remaining,BooleanSupplier cancelled);
        String answer(String question,List<JsonNode> evidence,Duration remaining,BooleanSupplier cancelled);
    }
    public interface Verifier { List<JsonNode> verify(List<JsonNode> hits,Duration remaining,BooleanSupplier cancelled); }
    public interface ModelGate { <T> T call(Supplier<T> action); }
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ThreadPoolExecutor MODELS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"research-model");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Gateway gateway;private final Model model;private final Verifier verifier;
    private final String allowedTool;private final OperationBoundary boundary;
    public ResearchRunner(Gateway gateway,Model model,Verifier verifier){this(gateway,model,verifier,"knowledge__knowledge_lookup_v1",OperationBoundary.unrestricted());}
    private ResearchRunner(Gateway gateway,Model model,Verifier verifier,String allowedTool,OperationBoundary boundary){this.gateway=Objects.requireNonNull(gateway);this.model=Objects.requireNonNull(model);this.verifier=Objects.requireNonNull(verifier);this.allowedTool=allowedTool;this.boundary=Objects.requireNonNull(boundary);}
    /** Formal workflow is pinned to v2; callers cannot choose another allowlist. */
    public static ResearchRunner authorized(Gateway gateway,Model model,Verifier verifier,OperationBoundary boundary){return new ResearchRunner(gateway,model,verifier,FormalKnowledgeMcpProtocol.EXPOSED_NAME,boundary);}
    public String run(String question,Duration budget,BooleanSupplier cancelled,Consumer<AgentExecutionEvent> events){
        return run(question,budget,cancelled,events,new ModelGate(){public <T>T call(Supplier<T> action){return action.get();}});
    }
    /** Only for a caller whose publisher authorizes its actual queue admission (for example bound BaseAgent).
     * The ordinary run method retains its own publication gate for plain consumers. */
    public String runWithAuthorizedPublisher(String question,Duration budget,BooleanSupplier cancelled,Consumer<AgentExecutionEvent> events){
        return run(question,budget,cancelled,new AuthorizedPublisher(Objects.requireNonNull(events)));
    }
    private record AuthorizedPublisher(Consumer<AgentExecutionEvent> target) implements Consumer<AgentExecutionEvent>{
        public void accept(AgentExecutionEvent event){target.accept(event);}
    }
    public String run(String question,Duration budget,BooleanSupplier cancelled,Consumer<AgentExecutionEvent> events,ModelGate gate){
        if(question==null||question.isBlank()||question.length()>4000||budget==null||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("RESEARCH_INVALID_REQUEST");
        long end=System.nanoTime()+budget.toNanos();check(end,cancelled);boundary.check(Phase.ENTRY);
        boundary.check(Phase.DISCOVERY);
        var tools=gateway.discover(remaining(end,cancelled),cancelled);
        if(tools.size()!=1||!allowedTool.equals(tools.get(0).name()))throw new IllegalStateException("RESEARCH_TOOL_ALLOWLIST");
        if(FormalKnowledgeMcpProtocol.EXPOSED_NAME.equals(allowedTool)&&!Objects.equals(new io.modelcontextprotocol.spec.McpSchema.Tool(allowedTool,"",tools.get(0).schema()).inputSchema(),FormalKnowledgeMcpProtocol.modelDefinition().inputSchema()))throw new IllegalStateException("RESEARCH_TOOL_SCHEMA_DENIED");
        var choice=bounded(()->{boundary.check(Phase.DEQUEUE);return gate.call(()->{boundary.check(Phase.MODEL_DISPATCH);return model.select(question,tools,remaining(end,cancelled),cancelled);});},end,cancelled);
        if(choice==null||!tools.get(0).name().equals(choice.name())||choice.callId()==null||choice.callId().length()>128||choice.arguments()==null||choice.arguments().length()>16384)throw new IllegalStateException("RESEARCH_TOOL_SELECTION_DENIED");
        // Validate before publishing arguments. Unknown fields must not leak into public tool events.
        var spec=new io.modelcontextprotocol.spec.McpSchema.Tool(tools.get(0).name(),tools.get(0).description(),tools.get(0).schema());
        var arguments=com.zxl.zxlaiagent.tools.support.StructuredMcpToolCallback.validateInput(choice.arguments(),spec);
        if(arguments.containsKey("budgetMs")||arguments.containsKey("authorization"))throw new IllegalArgumentException("Model cannot choose execution budget");
        check(end,cancelled);
        publish(events,AgentExecutionEvent.tool("TOOL_REQUESTED",1,choice.callId(),choice.name(),choice.arguments(),null,"requested",null));
        publish(events,AgentExecutionEvent.tool("TOOL_STARTED",1,choice.callId(),choice.name(),choice.arguments(),null,"running",null));
        long start=System.nanoTime();String result;
        try{boundary.check(Phase.TOOL_DISPATCH);result=gateway.call(choice.name(),choice.arguments(),remaining(end,cancelled),cancelled);check(end,cancelled);}
        catch(RuntimeException failure){
            if(failure instanceof SecurityFailure)throw failure;
            check(end,cancelled);
            String code=safeClientCode(failure);
            publish(events,AgentExecutionEvent.tool("TOOL_FAILED",1,choice.callId(),choice.name(),choice.arguments(),ToolResults.json(com.zxl.zxlaiagent.tools.model.ToolResult.fail(code,"MCP服务未完成调用，请检查服务状态")),"error",(System.nanoTime()-start)/1_000_000));throw failure;
        }
        if(result==null||result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>com.zxl.zxlaiagent.tools.support.StructuredMcpToolCallback.MAX_RESPONSE_BYTES){
            publish(events,AgentExecutionEvent.tool("TOOL_FAILED",1,choice.callId(),choice.name(),choice.arguments(),ToolResults.json(com.zxl.zxlaiagent.tools.model.ToolResult.fail("MCP_INVALID_RESPONSE","工具响应超过绝对字节限制")),"error",(System.nanoTime()-start)/1_000_000));
            throw new IllegalStateException("RESEARCH_RESPONSE_LIMIT");
        }
        JsonNode envelope=ToolResults.read(result);boolean success=envelope.path("success").asBoolean();
        publish(events,AgentExecutionEvent.tool(success?"TOOL_COMPLETED":"TOOL_FAILED",1,choice.callId(),choice.name(),choice.arguments(),result,success?"completed":"error",(System.nanoTime()-start)/1_000_000));
        if(!success){
            // Only the adapter's bounded diagnostic enum may classify a remote isError result.
            // Arbitrary remote text, retry hints and error messages never grant a retry or alter identity.
            String remote=envelope.path("data").path("remoteCode").asText();
            if("MCP_TOOL_ERROR".equals(envelope.path("code").asText())&&Set.of("MCP_DEADLINE_EXCEEDED","MCP_CANCELLED","MCP_CAPACITY").contains(remote))
                throw new com.zxl.zxlaiagent.knowledge.mcp.KnowledgeMcpClient.Failure(remote);
            throw new IllegalStateException("RESEARCH_REMOTE_FAILURE");
        }
        JsonNode raw=envelope.path("data").path("hits");
        if(!raw.isArray()||raw.size()>5)throw new IllegalStateException("RESEARCH_INVALID_EVIDENCE");
        List<JsonNode> hits=new ArrayList<>();raw.forEach(hits::add);
        if(hits.isEmpty()){boundary.check(Phase.RESULT_PUBLISH);return "当前有效知识中没有找到匹配资料；未生成缺乏证据的结论。 / No matching current evidence.";}
        boundary.check(Phase.EVIDENCE_READ);
        var checked=List.copyOf(verifier.verify(List.copyOf(hits),remaining(end,cancelled),cancelled));
        if(checked.isEmpty()||checked.size()!=hits.size())throw new IllegalStateException("RESEARCH_EVIDENCE_REJECTED");
        if(ToolResults.json(Map.of("question",question,"evidence",checked)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>22000)throw new IllegalStateException("RESEARCH_MODEL_INPUT_LIMIT");
        var answer=bounded(()->{boundary.check(Phase.DEQUEUE);return gate.call(()->{
            boundary.check(Phase.MODEL_DISPATCH);
            // Revalidate after both the model queue and the workflow model permit, immediately before dispatch.
            if(!verifier.verify(checked,remaining(end,cancelled),cancelled).equals(checked))throw new IllegalStateException("RESEARCH_EVIDENCE_CHANGED_BEFORE_DISPATCH");
            return model.answer(question,checked,remaining(end,cancelled),cancelled);
        });},end,cancelled);
        if(answer==null||answer.isBlank()||answer.length()>6000)throw new IllegalStateException("RESEARCH_INVALID_ANSWER");
        var matches=java.util.regex.Pattern.compile("\\[C(\\d+)\\]").matcher(answer);boolean cited=false;
        while(matches.find()){int id;try{id=Integer.parseInt(matches.group(1));}catch(NumberFormatException e){throw new IllegalStateException("RESEARCH_INVALID_CITATION");}if(id<1||id>checked.size())throw new IllegalStateException("RESEARCH_INVALID_CITATION");cited=true;}
        if(!cited)throw new IllegalStateException("RESEARCH_MISSING_CITATION");
        if(!verifier.verify(checked,remaining(end,cancelled),cancelled).equals(checked))throw new IllegalStateException("RESEARCH_EVIDENCE_CHANGED");
        check(end,cancelled);
        StringBuilder sources=new StringBuilder("\n\n引用 / Sources:");
        for(int i=0;i<checked.size();i++){var h=checked.get(i);sources.append("\n[C").append(i+1).append("] ").append(h.path("documentId").asText()).append(" · ").append(h.path("chunkId").asText()).append(" · L").append(h.path("startLine").asInt()).append("–").append(h.path("endLine").asInt());}
        return boundary.start(Phase.RESULT_PUBLISH,()->answer+sources);
    }
    /** Event consumer must only enqueue/start delivery; never block on a remote response inside a gate. */
    private void publish(Consumer<AgentExecutionEvent> events,AgentExecutionEvent event){
        if(events instanceof AuthorizedPublisher)events.accept(event);
        else boundary.start(Phase.RESULT_PUBLISH,()->{events.accept(event);return null;});
    }
    private static <T>T bounded(Supplier<T> action,long end,BooleanSupplier cancelled){
        check(end,cancelled);Future<T> f;
        try{f=MODELS.submit(()->{check(end,cancelled);return action.get();});}catch(RejectedExecutionException e){throw new IllegalStateException("RESEARCH_CAPACITY");}
        try{while(true){check(end,cancelled);try{return f.get(Math.min(50,Math.max(1,remaining(end,cancelled).toMillis())),TimeUnit.MILLISECONDS);}catch(TimeoutException poll){}}}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("RESEARCH_CANCELLED");}
        catch(ExecutionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("RESEARCH_MODEL_FAILED",e.getCause());}
        finally{if(!f.isDone())f.cancel(true);MODELS.purge();}
    }
    private static String safeClientCode(RuntimeException failure){
        String code=failure instanceof com.zxl.zxlaiagent.knowledge.mcp.KnowledgeMcpClient.Failure legacy?legacy.code():failure instanceof com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpClient.Failure formal?formal.code():null;
        if(code!=null&&Set.of("MCP_CLOSED","MCP_INVALID_ARGUMENT","MCP_NOT_AUTHORIZED","MCP_NETWORK_FAILURE","MCP_PROTOCOL_FAILURE","MCP_SCHEMA_CHANGED","MCP_DEADLINE_EXCEEDED","MCP_CANCELLED","MCP_CAPACITY","MCP_INVALID_RESPONSE").contains(code))return code;
        return "MCP_CALL_FAILED";
    }
    public static Duration remaining(long end,BooleanSupplier cancelled){check(end,cancelled);return Duration.ofNanos(Math.max(1,end-System.nanoTime()));}
    private static void check(long end,BooleanSupplier cancelled){if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new CancellationException("RESEARCH_CANCELLED");if(System.nanoTime()>=end)throw new IllegalStateException("RESEARCH_DEADLINE");}
}
