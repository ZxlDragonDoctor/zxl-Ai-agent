package com.zxl.zxlaiagent.knowledge.answer;
import java.util.*;
/** Operator diagnostics contain only reviewed codes and Java type names, never exception messages or material. */
final class AnswerFailureDiagnostics {
 private AnswerFailureDiagnostics(){}
 record Summary(String kind,String code,List<String> causes){}
 private static final Set<String> CODES=Set.of("INVALID_GENERATION_FORMAT_OR_SNAPSHOT","INVALID_JUDGE_FORMAT","MODEL_FAILED_OR_DEADLINE","MEMORY_CHANGED_BEFORE_DISPATCH","MEMORY_CHANGED_DURING_ANSWER","EVIDENCE_CHANGED","ALL_RETRIEVAL_CHANNELS_FAILED","NO_COMPATIBLE_INDEX_PROFILE");
 private static final Map<String,String> MESSAGES=Map.ofEntries(
  Map.entry("Invalid generation schema","GENERATION_SCHEMA_INVALID"),Map.entry("Invalid schema fields","MODEL_SCHEMA_FIELDS_INVALID"),
  Map.entry("Unknown schema field","MODEL_SCHEMA_FIELDS_INVALID"),Map.entry("Invalid claim schema","CLAIM_SCHEMA_INVALID"),
  Map.entry("Invalid missing information schema","MISSING_INFORMATION_SCHEMA_INVALID"),Map.entry("Invalid missing information item","MISSING_INFORMATION_SCHEMA_INVALID"),
  Map.entry("Incomplete chat response","MODEL_RESPONSE_INCOMPLETE"),Map.entry("Chat provider HTTP failure","MODEL_PROVIDER_HTTP_FAILURE"),
  Map.entry("Context budget exceeded","MODEL_CONTEXT_LIMIT"));
 static Summary summarize(Throwable failure){
  var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var causes=new ArrayList<String>();String code="UNCLASSIFIED",kind="INTERNAL";
  for(Throwable cause=failure;cause!=null&&causes.size()<16&&seen.add(cause);cause=cause.getCause()){
   String type=cause.getClass().getName();causes.add(type.substring(0,Math.min(200,type.length())));
   if(cause instanceof java.util.concurrent.TimeoutException||cause instanceof java.net.http.HttpTimeoutException||cause instanceof java.net.SocketTimeoutException)kind="TIMEOUT";
   else if(cause instanceof java.sql.SQLException)kind="DATABASE";
   else if(cause instanceof com.fasterxml.jackson.core.JsonProcessingException)kind="MODEL_JSON";
   else if(cause instanceof java.io.IOException&&!kind.equals("TIMEOUT"))kind="NETWORK";
   String message=cause.getMessage();if(CODES.contains(message==null?"":message))code=message;
   if(MESSAGES.containsKey(message==null?"":message))code=MESSAGES.get(message);
  }
  return new Summary(kind,code,List.copyOf(causes));
 }
}
