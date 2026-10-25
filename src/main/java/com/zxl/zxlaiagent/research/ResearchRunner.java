package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.tools.support.ToolResults;
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
    public ResearchRunner(Gateway gateway,Model model,Verifier verifier){this.gateway=gateway;this.model=model;this.verifier=verifier;}
    public String run(String question,Duration budget,BooleanSupplier cancelled,Consumer<AgentExecutionEvent> events){
        return run(question,budget,cancelled,events,new ModelGate(){public <T>T call(Supplier<T> action){return action.get();}});
    }
    public String run(String question,Duration budget,BooleanSupplier cancelled,Consumer<AgentExecutionEvent> events,ModelGate gate){
        if(question==null||question.isBlank()||question.length()>4000||budget==null||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("RESEARCH_INVALID_REQUEST");
        long end=System.nanoTime()+budget.toNanos();check(end,cancelled);
        var tools=gateway.discover(remaining(end,cancelled),cancelled);
        if(tools.size()!=1||!"knowledge__knowledge_lookup_v1".equals(tools.get(0).name()))throw new IllegalStateException("RESEARCH_TOOL_ALLOWLIST");
        var choice=bounded(()->gate.call(()->model.select(question,tools,remaining(end,cancelled),cancelled)),end,cancelled);
        if(choice==null||!tools.get(0).name().equals(choice.name())||choice.callId()==null||choice.callId().length()>128||choice.arguments()==null||choice.arguments().length()>16384)throw new IllegalStateException("RESEARCH_TOOL_SELECTION_DENIED");
        // Validate before publishing arguments. Unknown fields must not leak into public tool events.
        var spec=new io.modelcontextprotocol.spec.McpSchema.Tool(tools.get(0).name(),tools.get(0).description(),tools.get(0).schema());
        var arguments=com.zxl.zxlaiagent.tools.support.StructuredMcpToolCallback.validateInput(choice.arguments(),spec);
        if(arguments.containsKey("budgetMs"))throw new IllegalArgumentException("Model cannot choose execution budget");
        check(end,cancelled);
        events.accept(AgentExecutionEvent.tool("TOOL_REQUESTED",1,choice.callId(),choice.name(),choice.arguments(),null,"requested",null));
        events.accept(AgentExecutionEvent.tool("TOOL_STARTED",1,choice.callId(),choice.name(),choice.arguments(),null,"running",null));
        long start=System.nanoTime();String result;
        try{result=gateway.call(choice.name(),choice.arguments(),remaining(end,cancelled),cancelled);check(end,cancelled);}
        catch(RuntimeException failure){
            check(end,cancelled);
            events.accept(AgentExecutionEvent.tool("TOOL_FAILED",1,choice.callId(),choice.name(),choice.arguments(),ToolResults.json(com.zxl.zxlaiagent.tools.model.ToolResult.fail("MCP_CALL_FAILED","MCP服务未完成调用，请检查服务状态")),"error",(System.nanoTime()-start)/1_000_000));throw failure;
        }
        if(result==null||result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>com.zxl.zxlaiagent.tools.support.StructuredMcpToolCallback.MAX_RESPONSE_BYTES){
            events.accept(AgentExecutionEvent.tool("TOOL_FAILED",1,choice.callId(),choice.name(),choice.arguments(),ToolResults.json(com.zxl.zxlaiagent.tools.model.ToolResult.fail("MCP_INVALID_RESPONSE","工具响应超过绝对字节限制")),"error",(System.nanoTime()-start)/1_000_000));
            throw new IllegalStateException("RESEARCH_RESPONSE_LIMIT");
        }
        JsonNode envelope=ToolResults.read(result);boolean success=envelope.path("success").asBoolean();
        events.accept(AgentExecutionEvent.tool(success?"TOOL_COMPLETED":"TOOL_FAILED",1,choice.callId(),choice.name(),choice.arguments(),result,success?"completed":"error",(System.nanoTime()-start)/1_000_000));
        if(!success)throw new IllegalStateException("RESEARCH_REMOTE_FAILURE: "+envelope.path("code").asText());
        JsonNode raw=envelope.path("data").path("hits");
        if(!raw.isArray()||raw.size()>5)throw new IllegalStateException("RESEARCH_INVALID_EVIDENCE");
        List<JsonNode> hits=new ArrayList<>();raw.forEach(hits::add);
        if(hits.isEmpty())return "当前有效知识中没有找到匹配资料；未生成缺乏证据的结论。 / No matching current evidence.";
        var checked=List.copyOf(verifier.verify(List.copyOf(hits),remaining(end,cancelled),cancelled));
        if(checked.isEmpty()||checked.size()!=hits.size())throw new IllegalStateException("RESEARCH_EVIDENCE_REJECTED");
        if(ToolResults.json(Map.of("question",question,"evidence",checked)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>22000)throw new IllegalStateException("RESEARCH_MODEL_INPUT_LIMIT");
        var answer=bounded(()->gate.call(()->{
            // Revalidate after both the model queue and the workflow model permit, immediately before dispatch.
            if(!verifier.verify(checked,remaining(end,cancelled),cancelled).equals(checked))throw new IllegalStateException("RESEARCH_EVIDENCE_CHANGED_BEFORE_DISPATCH");
            return model.answer(question,checked,remaining(end,cancelled),cancelled);
        }),end,cancelled);
        if(answer==null||answer.isBlank()||answer.length()>6000)throw new IllegalStateException("RESEARCH_INVALID_ANSWER");
        var matches=java.util.regex.Pattern.compile("\\[C(\\d+)\\]").matcher(answer);boolean cited=false;
        while(matches.find()){int id;try{id=Integer.parseInt(matches.group(1));}catch(NumberFormatException e){throw new IllegalStateException("RESEARCH_INVALID_CITATION");}if(id<1||id>checked.size())throw new IllegalStateException("RESEARCH_INVALID_CITATION");cited=true;}
        if(!cited)throw new IllegalStateException("RESEARCH_MISSING_CITATION");
        if(!verifier.verify(checked,remaining(end,cancelled),cancelled).equals(checked))throw new IllegalStateException("RESEARCH_EVIDENCE_CHANGED");
        check(end,cancelled);
        StringBuilder sources=new StringBuilder("\n\n引用 / Sources:");
        for(int i=0;i<checked.size();i++){var h=checked.get(i);sources.append("\n[C").append(i+1).append("] ").append(h.path("documentId").asText()).append(" · ").append(h.path("chunkId").asText()).append(" · L").append(h.path("startLine").asInt()).append("–").append(h.path("endLine").asInt());}
        return answer+sources;
    }
    private static <T>T bounded(Supplier<T> action,long end,BooleanSupplier cancelled){
        check(end,cancelled);Future<T> f;
        try{f=MODELS.submit(()->{check(end,cancelled);return action.get();});}catch(RejectedExecutionException e){throw new IllegalStateException("RESEARCH_CAPACITY");}
        try{while(true){check(end,cancelled);try{return f.get(Math.min(50,Math.max(1,remaining(end,cancelled).toMillis())),TimeUnit.MILLISECONDS);}catch(TimeoutException poll){}}}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("RESEARCH_CANCELLED");}
        catch(ExecutionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("RESEARCH_MODEL_FAILED",e.getCause());}
        finally{if(!f.isDone())f.cancel(true);MODELS.purge();}
    }
    public static Duration remaining(long end,BooleanSupplier cancelled){check(end,cancelled);return Duration.ofNanos(Math.max(1,end-System.nanoTime()));}
    private static void check(long end,BooleanSupplier cancelled){if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new CancellationException("RESEARCH_CANCELLED");if(System.nanoTime()>=end)throw new IllegalStateException("RESEARCH_DEADLINE");}
}
