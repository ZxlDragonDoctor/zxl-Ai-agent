package com.zxl.zxlaiagent.knowledge.answer;

import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;

import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.knowledge.retrieval.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** One request-local immutable evidence snapshot, one generation, no unbounded repair. Unsupported claims are removed. */
public final class AnswerService {
 public record Request(String question,String conversationId,String mode,long budgetMs,String profile) { }
 public record Claim(String text,List<String> evidenceIds) { }
 public record CheckedClaim(String text,List<String> evidenceIds,String support,String reason) { }
 public record Evidence(String id,String snapshotId,String documentId,String revisionId,String chunkId,String sourceName,
    int startUtf16,int endUtf16,int startLine,int endLine,String text,String sha256,boolean truncated,long generation) {
  /** Synthetic non-database fixture convenience; live snapshots always carry actual document generation. */
  public Evidence(String id,String snapshotId,String documentId,String revisionId,String chunkId,String sourceName,int startUtf16,int endUtf16,int startLine,int endLine,String text,String sha256,boolean truncated){this(id,snapshotId,documentId,revisionId,chunkId,sourceName,startUtf16,endUtf16,startLine,endLine,text,sha256,truncated,0);}
 }
 public record Response(String traceId,String snapshotId,String status,String answer,List<CheckedClaim> claims,List<Evidence> evidence,
    String reason,List<String> missingInformation,String generatorIdentity,String judgeIdentity,long elapsedMs,int removedClaims,MemoryContext.MemoryView memory) { }
 private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"answer-model");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 private static <T> T bounded(Supplier<T> task,long end){Future<T> f=WORKERS.submit(task::get);try{return f.get(remaining(end).toNanos(),TimeUnit.NANOSECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("CANCELLED");}catch(Exception e){if(e.getCause() instanceof com.zxl.zxlaiagent.security.SecurityFailure denied)throw denied;throw new IllegalStateException("MODEL_FAILED_OR_DEADLINE",e);}finally{f.cancel(true);WORKERS.purge();}}
 private final OperationBoundary boundary;private final java.util.function.Consumer<String> conversationVerifier;private final boolean trusted;private final java.util.function.Function<String,OperationBoundary> conversationBoundary;
 private final KnowledgeDatabase db;private final String tenant;private final RetrievalService retrieval;private final AnswerModel real;private final MemoryContext memory;
 public AnswerService(KnowledgeDatabase db,String tenant,RetrievalService retrieval,AnswerModel real,MemoryContext memory){this(db,tenant,retrieval,real,memory,OperationBoundary.unrestricted(),id->{},false);}
 public AnswerService(KnowledgeDatabase db,String tenant,RetrievalService retrieval,AnswerModel real,MemoryContext memory,OperationBoundary boundary,java.util.function.Consumer<String> conversationVerifier,boolean trusted){this(db,tenant,retrieval,real,memory,boundary,conversationVerifier,trusted,null);}
 public AnswerService(KnowledgeDatabase db,String tenant,RetrievalService retrieval,AnswerModel real,MemoryContext memory,OperationBoundary boundary,java.util.function.Consumer<String> conversationVerifier,boolean trusted,java.util.function.Function<String,OperationBoundary> conversationBoundary){this.conversationBoundary=conversationBoundary;this.boundary=Objects.requireNonNull(boundary);this.conversationVerifier=Objects.requireNonNull(conversationVerifier);this.trusted=trusted;this.db=db;this.tenant=tenant;this.retrieval=retrieval;this.real=real;this.memory=memory;}
 public Response answer(Request request){
  if(conversationBoundary!=null&&request!=null){return new AnswerService(db,tenant,retrieval,real,memory,conversationBoundary.apply(request.conversationId()),conversationVerifier,trusted).answer(request);}
  boundary.check(Phase.ENTRY);if(trusted&&request!=null&&request.profile()!=null)throw new IllegalArgumentException("Identity profile forbidden");validate(request);conversationVerifier.accept(request.conversationId());long started=System.nanoTime();long end=started+Duration.ofMillis(request.budgetMs()).toNanos();
  String trace=UUID.randomUUID().toString(),snapshot=UUID.randomUUID().toString();
  String profile=trusted?"authenticated":request.profile()==null?"alice":request.profile();AnswerModel model="real".equals(request.mode())?real:AnswerModel.demo();
  MemoryContext.MemoryView mem=new MemoryContext.MemoryView(List.of(),"not-read",0,0);
  if(model==null)return response(trace,snapshot,"SERVICE_FAILURE",List.of(),List.of(),"REAL_MODEL_NOT_CONFIGURED",List.of(),"unavailable","unavailable",started,0,mem);
  try{
   mem=memory.read(profile,request.conversationId(),remaining(end));
   if(request.question().matches("(?s).*(VPN|vpn|连接).*")&&!request.question().matches("(?s).*\\d{3}.*")&&!request.question().contains("通用"))
    return response(trace,snapshot,"NEEDS_CLARIFICATION",List.of(),List.of(),"MISSING_ERROR_CODE",List.of("请提供错误码或产品版本，例如 VPN 809。"),model.identity(),model.judgeIdentity(),started,0,mem);
   long retrieveBudget=Math.min(10000,Math.max(100,remaining(end).toMillis()-300));
   var result=retrieval.retrieve(new RetrievalService.Request(request.question(),"hybrid",5,20,false,false,retrieveBudget));
   remaining(end);var selected=snapshot(snapshot,result.hits(),end);List<Evidence> es=selected.evidence();int quarantined=selected.quarantined();
   if(es.isEmpty())return response(trace,snapshot,"INSUFFICIENT_EVIDENCE",List.of(),List.of(),quarantined>0?"QUARANTINED_UNTRUSTED_SOURCE":"NO_CURRENT_EVIDENCE",List.of("请补充相关资料或更具体的问题。"),model.identity(),model.judgeIdentity(),started,0,mem);
   final List<Evidence> dispatchEvidence=es;final MemoryContext.MemoryView dispatchMemory=mem;
   var draft=bounded(()->{if(!memory.read(profile,request.conversationId(),remaining(end)).equals(dispatchMemory))throw new IllegalStateException("MEMORY_CHANGED_BEFORE_DISPATCH");if(dispatchEvidence.stream().anyMatch(e->untrustedSource(e.text())))throw new IllegalStateException("QUARANTINED_UNTRUSTED_SOURCE");if(!current(dispatchEvidence,end))throw new IllegalStateException("EVIDENCE_CHANGED");return model.generate(request.question(),snapshot,dispatchEvidence,dispatchMemory,remaining(end),boundary);},end);
   if(draft==null||!snapshot.equals(draft.snapshotId())||draft.claims()==null||draft.claims().size()>8||!validMissing(draft.missingInformation()))throw new IllegalStateException("INVALID_GENERATION_FORMAT_OR_SNAPSHOT");
   List<CheckedClaim> accepted=new ArrayList<>();int removed=0;
   for(var claim:draft.claims()){
    String invalid=deterministicFailure(claim,es);
    if(invalid!=null){removed++;continue;}
    var refs=es.stream().filter(e->claim.evidenceIds().contains(e.id())).toList();
    // Validate immediately before every external judge dispatch, and once again before publication.
    if(!current(refs,end)){removed++;continue;}
    var verdict=bounded(()->{if(!current(refs,end))throw new IllegalStateException("EVIDENCE_CHANGED");return model.judge(request.question(),claim,refs,remaining(end),boundary);},end);
    if(verdict==null||!Set.of("SUPPORTED","CONTRADICTED","INSUFFICIENT").contains(verdict.support())||verdict.reason()==null||verdict.reason().length()>500)throw new IllegalStateException("INVALID_JUDGE_FORMAT");
    if("SUPPORTED".equals(verdict.support()))accepted.add(new CheckedClaim(claim.text(),List.copyOf(claim.evidenceIds()),verdict.support(),verdict.reason()));else removed++;
   }
   if(!memory.read(profile,request.conversationId(),remaining(end)).equals(mem))throw new IllegalStateException("MEMORY_CHANGED_DURING_ANSWER");
   if(!current(es,end))return response(trace,snapshot,"INSUFFICIENT_EVIDENCE",List.of(),List.of(),"EVIDENCE_CHANGED_DURING_ANSWER",List.of("资料已更新或删除，请重试。"),model.identity(),model.judgeIdentity(),started,draft.claims().size(),mem);
   var ids=accepted.stream().flatMap(c->c.evidenceIds().stream()).collect(java.util.stream.Collectors.toSet());
   es=es.stream().filter(e->ids.contains(e.id())).toList();
   String status=accepted.isEmpty()?"INSUFFICIENT_EVIDENCE":removed>0||quarantined>0||!draft.missingInformation().isEmpty()?"PARTIAL":"ANSWERED";
   return response(trace,snapshot,status,accepted,es,quarantined>0?"QUARANTINED_UNTRUSTED_SOURCE":accepted.isEmpty()?"NO_SUPPORTED_CLAIMS":removed>0?"UNSUPPORTED_CLAIMS_REMOVED":!draft.missingInformation().isEmpty()?"MISSING_SUBQUESTION_EVIDENCE":"SUPPORTED_CURRENT_EVIDENCE",List.copyOf(draft.missingInformation()),model.identity(),model.judgeIdentity(),started,removed,mem);
  }catch(RuntimeException failure){
   if(failure instanceof com.zxl.zxlaiagent.security.SecurityFailure)throw failure;
   return response(trace,snapshot,"SERVICE_FAILURE",List.of(),List.of(),Thread.currentThread().isInterrupted()?"CANCELLED":"RETRIEVAL_GENERATION_OR_VALIDATION_FAILED",List.of("服务未完成证据校验，请稍后重试。"),model.identity(),model.judgeIdentity(),started,0,new MemoryContext.MemoryView(List.of(),"discarded-on-failure",0,0));
  }
 }
 private record SelectedEvidence(List<Evidence> evidence,int quarantined) { }
 private SelectedEvidence snapshot(String snapshot,List<RetrievalService.Hit> hits,long end){boundary.check(Phase.SOURCE_READ);
  return db.tx(remaining(end),c->{List<Evidence> list=new ArrayList<>();int budget=4000;int quarantined=0;
   for(var h:hits){if(untrustedSource(h.text())){quarantined++;continue;}if(budget<=0)continue;
    try(var s=KnowledgeDatabase.statement(c,"SELECT d.source_name,r.raw_text FROM knowledge_document d JOIN knowledge_revision r ON r.tenant_id=d.tenant_id AND r.document_id=d.document_id AND r.revision_id=d.active_revision WHERE d.tenant_id=? AND d.document_id=? AND d.active_revision=? AND d.version=? AND d.status='ACTIVE' AND r.state='ACTIVE'",tenant,h.documentId(),h.revisionId(),h.generation());var rows=s.executeQuery()){
     if(!rows.next())continue;String raw=rows.getString(2);int length=Math.min(Math.min(h.text().length(),1200),budget);
     if(h.startUtf16()<0||h.endUtf16()>raw.length()||h.endUtf16()-h.startUtf16()!=h.text().length()||!raw.substring(h.startUtf16(),h.endUtf16()).equals(h.text())||line(raw,h.startUtf16())!=h.startLine()||line(raw,Math.max(h.startUtf16(),h.endUtf16()-1))!=h.endLine())continue;
     if(length>0&&Character.isHighSurrogate(h.text().charAt(length-1)))length--;
     if(length<=0)continue;String text=h.text().substring(0,length);int lineEnd=line(raw,h.startUtf16()+length-1);
     list.add(new Evidence("E"+(list.size()+1),snapshot,h.documentId(),h.revisionId(),h.chunkId(),rows.getString(1),h.startUtf16(),h.startUtf16()+length,h.startLine(),lineEnd,text,hash(text),length<h.text().length(),h.generation()));budget-=length;
    }
   }return new SelectedEvidence(List.copyOf(list),quarantined);
  });
 }
 private boolean current(List<Evidence> es,long end){boundary.check(Phase.SOURCE_READ);return db.tx(remaining(end),c->{for(var e:es){try(var s=KnowledgeDatabase.statement(c,"SELECT k.text_content,k.start_utf16,k.end_utf16,r.raw_text,d.version FROM knowledge_chunk k JOIN knowledge_document d ON d.tenant_id=k.tenant_id AND d.document_id=k.document_id AND d.active_revision=k.revision_id JOIN knowledge_revision r ON r.tenant_id=k.tenant_id AND r.document_id=k.document_id AND r.revision_id=k.revision_id WHERE k.tenant_id=? AND k.document_id=? AND k.revision_id=? AND k.chunk_id=? AND d.status='ACTIVE' AND r.state='ACTIVE'",tenant,e.documentId(),e.revisionId(),e.chunkId());var r=s.executeQuery()){
    if(!r.next()||e.generation()!=r.getLong(5)||e.startUtf16()!=r.getInt(2)||e.endUtf16()>r.getInt(3)||!r.getString(1).startsWith(e.text())||!r.getString(4).substring(e.startUtf16(),e.endUtf16()).equals(e.text())||!hash(e.text()).equals(e.sha256()))return false;
   }}return true;});}
 /** Conservative v2 quarantine for explicit source override instructions; not a general injection detector. */
 public static boolean untrustedSource(String text){return text!=null&&Pattern.compile("(?is)(忽略.{0,30}(指令|规则|提示)|ignore.{0,40}(previous|system|instruction|rule)|系统指令|system instruction|(?:泄露|输出).{0,20}(密钥|系统提示))").matcher(text).find();}
 public static String deterministicFailure(Claim c,List<Evidence> es){
  if(c==null||c.text()==null||c.text().isBlank()||c.text().length()>600||c.evidenceIds()==null||c.evidenceIds().isEmpty()||c.evidenceIds().size()>3)return "INVALID_CLAIM_FORMAT";
  if(c.text().matches("(?si).*(忽略.{0,20}(指令|规则)|ignore.{0,30}(instruction|rule)|泄露.{0,10}(密钥|系统)).*"))return "UNTRUSTED_INSTRUCTION";
  var ids=es.stream().map(Evidence::id).collect(java.util.stream.Collectors.toSet());
  var inline=Pattern.compile("\\[(E[0-9]+)\\]").matcher(c.text());while(inline.find())if(!c.evidenceIds().contains(inline.group(1)))return "UNDECLARED_INLINE_CITATION";
  if(new HashSet<>(c.evidenceIds()).size()!=c.evidenceIds().size()||!ids.containsAll(c.evidenceIds()))return "UNKNOWN_OR_DUPLICATE_CITATION";
  String source=es.stream().filter(e->c.evidenceIds().contains(e.id())).map(Evidence::text).reduce("",(a,b)->a+"\n"+b);
  var numbers=Pattern.compile("\\d+(?:[.:-]\\d+)*").matcher(c.text());while(numbers.find())if(!Pattern.compile("(?<!\\d)"+Pattern.quote(numbers.group())+"(?!\\d)").matcher(source).find())return "UNSUPPORTED_NUMBER";
  if((c.text().contains("不")||c.text().contains("禁止")||c.text().toLowerCase(Locale.ROOT).contains("not "))&&!source.contains(c.text()))return "NEGATION_REQUIRES_REVIEW";
  return null;
 }
 private static int line(String raw,int offset){int n=1;for(int i=0;i<offset;i++){char ch=raw.charAt(i);if(ch=='\n'||ch=='\r'&&(i+1>=raw.length()||raw.charAt(i+1)!='\n'))n++;}return n;}
 private static Duration remaining(long end){if(Thread.currentThread().isInterrupted())throw new IllegalStateException("CANCELLED");long nanos=end-System.nanoTime();if(nanos<=0)throw new IllegalStateException("DEADLINE_EXCEEDED");return Duration.ofNanos(nanos);}
 private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private static boolean validMissing(List<String> missing){return missing!=null&&missing.size()<=5&&missing.stream().allMatch(x->x!=null&&!x.isBlank()&&x.length()<=300)&&missing.stream().mapToInt(String::length).sum()<=1000&&new HashSet<>(missing).size()==missing.size();}
 public static void validate(Request r){if(r==null||r.question()==null||r.question().isBlank()||r.question().length()>2000||r.conversationId()==null||!r.conversationId().matches("[A-Za-z0-9_-]{1,80}")||!Set.of("demo","real").contains(r.mode()==null?"":r.mode())||r.budgetMs()<100||r.budgetMs()>30000||r.profile()!=null&&!Set.of("alice","bob").contains(r.profile()))throw new IllegalArgumentException("question 1..2000; conversationId 1..80 letters/digits/_/-; mode demo|real; budgetMs 100..30000; profile alice|bob");}
 private Response response(String trace,String snapshot,String status,List<CheckedClaim> claims,List<Evidence> es,String reason,List<String> missing,String generator,String judge,long start,int removed,MemoryContext.MemoryView memory){
  String answer=claims.stream().map(c->c.text()+" "+c.evidenceIds().stream().map(id->"["+id+"]").reduce("",String::concat)).reduce("",(a,b)->a.isEmpty()?b:a+"\n\n"+b);
  if(answer.isEmpty())answer=switch(status){case "NEEDS_CLARIFICATION"->"请提供错误码或产品版本，以便查找对应资料。";case "SERVICE_FAILURE"->"本次服务未完成，未生成经过校验的答案。";default->"当前有效资料不足以支持回答。";};
  boundary.check(Phase.RESULT_PUBLISH);return new Response(trace,snapshot,status,answer,List.copyOf(claims),List.copyOf(es),reason,missing,generator,judge,(System.nanoTime()-start)/1000000,removed,memory);
 }
}
