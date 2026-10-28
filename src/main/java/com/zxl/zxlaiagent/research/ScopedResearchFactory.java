package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.knowledge.mcp.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.*;
import com.zxl.zxlaiagent.tools.support.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Every runner captures one immutable actor. No tenant is retained in a singleton connection. */
public final class ScopedResearchFactory {
 private final FormalKnowledgeMcpClient mcp;private final ScopedKnowledgeClient knowledge;
 private final DashScopeAgentStreamer streamer;private final AuthorizationService authorization;
 private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 public ScopedResearchFactory(FormalKnowledgeMcpClient mcp,ScopedKnowledgeClient knowledge,DashScopeAgentStreamer streamer,AuthorizationService authorization){this.mcp=Objects.requireNonNull(mcp);this.knowledge=Objects.requireNonNull(knowledge);this.streamer=Objects.requireNonNull(streamer);this.authorization=Objects.requireNonNull(authorization);}
 public ResearchRunner create(ActorRef actor,String runId){
  return create(actor,runId,List.of());
 }
 /** All additional permissions are chosen by Java, not deserialized from model or browser arguments. */
 public ResearchRunner create(ActorRef actor,String runId,List<AuthorizationService.Permission> additional){
  if(additional==null||additional.size()>6||additional.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Bounded Java permissions required");
  var required=new LinkedHashSet<AuthorizationService.Permission>();required.add(new AuthorizationService.Permission("agent.execute",null));required.addAll(additional);
  var dispatch=List.copyOf(required);required.add(new AuthorizationService.Permission("knowledge.read",null));
  return build(actor,new ActorBoundary(authorization,Objects.requireNonNull(actor),List.copyOf(required),runId),dispatch,runId);
 }
 /** Package-only seam for deterministic boundary tests; production callers use the explicit permission list. */
 ResearchRunner create(ActorRef actor,OperationBoundary boundary){return build(actor,boundary,List.of(new AuthorizationService.Permission("agent.execute",null)),null);}
 private ResearchRunner build(ActorRef actor,OperationBoundary boundary,List<AuthorizationService.Permission> permissions,String runId){
  Objects.requireNonNull(actor);Objects.requireNonNull(boundary);
  ResearchRunner.Gateway gateway=new ResearchRunner.Gateway(){
   public List<ResearchRunner.Tool> discover(Duration budget,BooleanSupplier cancelled){
    boundary.check(Phase.DISCOVERY);
    var tools=mcp.discover(budget,cancelled).stream().map(t->new ResearchRunner.Tool(t.exposedName(),t.definition().description(),ToolResults.json(t.definition().inputSchema()))).toList();
    boundary.check(Phase.DISCOVERY);return tools;
   }
   public String call(String name,String input,Duration budget,BooleanSupplier cancelled){
    if(!FormalKnowledgeMcpProtocol.EXPOSED_NAME.equals(name))throw new SecurityFailure("ACTION_DENIED",403);
    var arguments=StructuredMcpToolCallback.validateInput(input,FormalKnowledgeMcpProtocol.modelDefinition());
    FormalKnowledgeMcpProtocol.modelArguments(arguments);boundary.check(Phase.TOOL_DISPATCH);
    final McpSchema.CallToolResult result;
    try{result=mcp.call(actor,name,arguments,budget,cancelled,permissions,runId);}
    catch(FormalKnowledgeMcpClient.Failure failed){if("MCP_NOT_AUTHORIZED".equals(failed.code()))throw new SecurityFailure("ACTION_DENIED",403);throw failed;}
    // The generic legacy adapter omits unknown remote diagnostics. Preserve known authorization semantics here.
    if(result!=null&&Boolean.TRUE.equals(result.isError())&&result.content()!=null&&result.content().size()==1&&result.content().get(0) instanceof McpSchema.TextContent text&&text.text()!=null&&text.text().length()<=4096){
     String remote=null;try{remote=ToolResults.read(text.text()).path("code").asText();}catch(RuntimeException malformed){/* safe generic adapter below */}
     if(Set.of("MCP_NOT_AUTHORIZED","MCP_UNAUTHORIZED","MCP_NOT_VISIBLE").contains(remote==null?"":remote))throw new SecurityFailure("ACTION_DENIED",403);
    }
    boundary.check(Phase.RESULT_PUBLISH);return StructuredMcpToolCallback.views(StructuredMcpToolCallback.adaptResult(result)).original();
   }
  };
  McpEvidenceVerifier.VerificationPort port=(id,budget,cancelled)->{
   if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("RESEARCH_CANCELLED");
   if(!id.matches("[A-Za-z0-9_-]{1,120}"))throw new IllegalStateException("EVIDENCE_DOCUMENT_ID");
   var response=knowledge.exchange(actor,"GET","/ai/learning/knowledge/documents/"+id,null,new byte[0],budget,cancelled,permissions,runId);
   if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("RESEARCH_CANCELLED");
   if(response.status()!=200)throw new SecurityFailure(response.status()==401||response.status()==403?"ACTION_DENIED":"RESOURCE_UNAVAILABLE",response.status()==401?401:response.status()==403?403:404);
   try{return json.readTree(response.body());}catch(java.io.IOException invalid){throw new IllegalStateException("EVIDENCE_GATEWAY_FAILED");}
  };
  return ResearchRunner.authorized(gateway,new StreamingResearchModel(streamer,boundary),new McpEvidenceVerifier(port,boundary),boundary);
 }
}
