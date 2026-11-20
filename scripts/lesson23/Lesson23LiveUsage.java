import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.usage.*;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import java.nio.file.*;
import java.util.*;
class Lesson23LiveUsage {
 public static void main(String[] args)throws Exception {
  String credential=System.getenv("LESSON23_MODEL_KEY");
  if(credential==null||credential.isBlank())throw new IllegalArgumentException("Explicit model credential required");
  var json=new ObjectMapper();var result=new LinkedHashMap<String,Object>();String tenant="lesson23-live-"+UUID.randomUUID();
  try(var db=SecurityDatabase.fromEnvironment()){
   db.tx(c->{try(var s=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES (?)",tenant)){s.executeUpdate();}return null;});
   var ledger=new TokenBudgetLedger(db,tenant);ledger.createAccount("acceptance",10000,2);
   var streamer=new DashScopeAgentStreamer(json,credential,"https://dashscope.aliyuncs.com/compatible-mode/v1","qwen-plus",false,0);
   long started=System.nanoTime(),end=started+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
   result.put("model","qwen-plus");result.put("scope","live-provider direct streamer + PG ledger, not authenticated HTTP workflow");result.put("tenant",tenant);
   try{var response=streamer.stream("Return only OK.",List.of(new UserMessage("Reply OK.")),new ToolCallback[0],(a,b)->{},()->System.nanoTime()>=end,OperationBoundary.unrestricted(),new TokenCallBudget(ledger,"acceptance","call-1",4096));result.put("outcome","returned");result.put("answer",response.getResult().getOutput().getText());result.put("reportedTotal",response.getMetadata().getUsage().getTotalTokens());}
   catch(RuntimeException failure){result.put("outcome","failed");result.put("failureType",failure.getClass().getSimpleName());}
   result.put("elapsedMs",(System.nanoTime()-started)/1000000);result.put("account",ledger.account("acceptance"));
   result.put("call",db.tx(c->{try(var s=SecurityDatabase.statement(c,"SELECT state,estimate,actual FROM token_budget_call WHERE tenant_id=? AND account_id='acceptance' AND call_id='call-1'",tenant);var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Missing call");var o=new LinkedHashMap<String,Object>();o.put("state",r.getString(1));o.put("estimate",r.getLong(2));o.put("actual",r.getObject(3));return o;}}));
  }
  json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/lesson23-live-usage.json").toFile(),result);
  Object total=result.get("reportedTotal");
  if(!"returned".equals(result.get("outcome"))||!(total instanceof Number)
    ||((TokenBudgetLedger.Account)result.get("account")).spent()!=((Number)total).longValue()
    ||((TokenBudgetLedger.Account)result.get("account")).reserved()!=0)
   throw new IllegalStateException("Observation saved; completed known-usage settlement not verified");
 }
}
