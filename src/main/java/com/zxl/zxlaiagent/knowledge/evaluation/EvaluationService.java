package com.zxl.zxlaiagent.knowledge.evaluation;

import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService;
import com.zxl.zxlaiagent.knowledge.retrieval.Reranker;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.zxl.zxlaiagent.knowledge.KnowledgeDatabase.statement;

/** Opt-in synthetic imports are isolated from the user's knowledge documents. All retrieval uses PG. */
public final class EvaluationService {
    private final KnowledgeDatabase db;private final KnowledgeEmbedding configured;private final Reranker configuredReranker;private final AtomicBoolean busy=new AtomicBoolean();
    public EvaluationService(KnowledgeDatabase db,KnowledgeEmbedding configured){this(db,configured,Reranker.rules());}
    public EvaluationService(KnowledgeDatabase db,KnowledgeEmbedding configured,Reranker reranker){this.db=db;this.configured=configured;this.configuredReranker=Objects.requireNonNull(reranker);}
    public record Snapshots(boolean demoReady,boolean realReady){}
    public record Status(String datasetVersion,int questionCount,Map<String,Integer> splits,boolean snapshotReady,String modelIdentity,String storage,String boundary,boolean realAvailable,Snapshots snapshots){}
    public record Input(String split,Integer topK,Boolean real,Long budgetMs){public Input(String split,Integer topK,Boolean real){this(split,topK,real,null);}}
    public record Config(int topK,String split,long budgetMs,int concurrency){}
    public record QueryResult(String queryId,String question,String split,List<String> ranking,EvaluationMetrics.Score score,long elapsedMs,String error,List<String> degradations,String reranker,List<RetrievalService.Stage> stages){
        public QueryResult(String id,String question,String split,List<String> ranking,EvaluationMetrics.Score score,long elapsed,String error,List<String> degradations,String reranker){this(id,question,split,ranking,score,elapsed,error,degradations,reranker,List.of());}
    }
    public record Latency(long p50Ms,long p95Ms,int samples){}
    public record Metrics(Double recall,double mrr,Double ndcg,int noAnswerCount,Double noAnswerEmptyRate,int forbiddenCount,long p50Ms,long p95Ms,int errorCount,int evaluatedCount,Map<String,Latency> stageLatency){}
    public record Strategy(String mode,String split,Metrics metrics,List<QueryResult> queries){}
    public record Report(String datasetVersion,String snapshotId,String modelIdentity,boolean realSemantic,int queryCount,boolean complete,String boundary,Config config,int embeddingCalls,int embeddingCacheHits,long elapsedMs,List<Strategy> strategies,EvaluationDataset dataset,String rerankerIdentity,boolean realReranker,int rerankerCalls){}
    private static final String BOUNDARY="固定合成22问；真实PostgreSQL；demo向量仅验证管线，不证明中文语义；单次串行、同问题embedding缓存共享，不是冷暖缓存或并发性能基准；Recall/nDCG排除无答案，MRR包含无答案0分；阈值预设：禁止块=0、错误=0，其余质量指标只观察，不预设混合必胜。";
    private boolean realAvailable(){return configured.identity().startsWith("http:");}
    private KnowledgeEmbedding embedding(boolean real){if(real&&!realAvailable())throw new IllegalArgumentException("真实Embedding未配置，禁止回退demo");return real?configured:new DemoKnowledgeEmbedding();}
    public Status status(){
        var demo=EvaluationDataset.create(new DemoKnowledgeEmbedding().identity(),8);boolean dr=ready(demo),rr=false;
        if(realAvailable())rr=ready(EvaluationDataset.create(configured.identity(),configured.dimensions()));
        return new Status(demo.version(),demo.questions().size(),Map.of("tuning",9,"holdout",13),dr,configured.identity(),"PostgreSQL + pgvector",BOUNDARY,realAvailable(),new Snapshots(dr,rr));
    }
    public Status seed(boolean real){
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("评测或导入正在执行，请稍后重试");
        try{var e=embedding(real);var data=EvaluationDataset.create(e.identity(),e.dimensions());
            // First publish old revision, then current; retirement is the real lesson-12 publication path.
            var ordered=new ArrayList<>(data.documents());ordered.sort(Comparator.comparing(EvaluationDataset.Document::active));
            for(var doc:ordered)new KnowledgeService(db,doc.tenant(),e).importDocument(doc.id(),doc.source(),doc.text());
            verify(data);return status();
        }finally{busy.set(false);}
    }
    private boolean ready(EvaluationDataset data){try{verify(data);return true;}catch(IllegalArgumentException missing){return false;}}
    private void verify(EvaluationDataset data){
        Set<String> actual=db.tx(c->{Set<String> ids=new HashSet<>();
            try(var s=statement(c,"SELECT k.chunk_id,k.revision_id,k.model_identity,k.dimensions,r.profile_hash FROM knowledge_chunk k JOIN knowledge_document d ON d.tenant_id=k.tenant_id AND d.document_id=k.document_id AND d.active_revision=k.revision_id JOIN knowledge_revision r ON r.tenant_id=k.tenant_id AND r.document_id=k.document_id AND r.revision_id=k.revision_id WHERE k.tenant_id=? AND d.status='ACTIVE'",EvaluationDataset.tenant(data.modelIdentity()));var r=s.executeQuery()){
                while(r.next()){String id=r.getString(1);var expected=data.chunks().get(id);
                    if(expected==null||!expected.revisionId().equals(r.getString(2))||!data.modelIdentity().equals(r.getString(3))||data.dimensions()!=r.getInt(4)||!expected.profileHash().equals(r.getString(5)))throw new IllegalArgumentException("固定集快照或模型空间已变化，请显式重新导入");ids.add(id);}
            }return ids;});
        data.validate(data.snapshotId(),data.modelIdentity(),actual);
    }
    public Report run(Input input){
        String split=input==null||input.split()==null?"all":input.split();int k=input==null||input.topK()==null?3:input.topK();boolean real=input!=null&&Boolean.TRUE.equals(input.real());
        long budget=input==null||input.budgetMs()==null?60000:input.budgetMs();if(budget<1000||budget>120000)throw new IllegalArgumentException("batch budgetMs 1000..120000");
        if(!Set.of("all","tuning","holdout").contains(split)||k<1||k>5)throw new IllegalArgumentException("split all|tuning|holdout, topK 1..5");
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("评测或导入正在执行，请稍后重试");
        try{long start=System.nanoTime();long deadline=start+Duration.ofMillis(budget).toNanos();var cached=new CachedEmbedding(embedding(real));var data=EvaluationDataset.create(cached.identity(),cached.dimensions());verify(data);
            var selected=data.questions().stream().filter(q->split.equals("all")||split.equals(q.split())).toList();
            Map<String,List<QueryResult>> rows=new LinkedHashMap<>();for(String mode:List.of("keyword","vector","hybrid","rerank"))for(String group:List.of("tuning","holdout"))rows.put(mode+":"+group,new ArrayList<>());
            var chosenReranker=real?configuredReranker:Reranker.rules();var rerankerCalls=new java.util.concurrent.atomic.AtomicInteger();
            Reranker measuredReranker=new Reranker(){public String identity(){return chosenReranker.identity();}public List<Score> rank(String query,List<RetrievalService.Hit> hits,Duration remaining){rerankerCalls.incrementAndGet();return chosenReranker.rank(query,hits,remaining);}};
            var service=new RetrievalService(db,EvaluationDataset.tenant(data.modelIdentity()),cached,measuredReranker);boolean complete=true;
            for(var question:selected){if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Evaluation cancelled");
                var result=evaluateQuestion(service,data,question,k,deadline);
                for(int i=0;i<result.size();i++)rows.get(List.of("keyword","vector","hybrid","rerank").get(i)+":"+result.get(i).split()).add(result.get(i));
                if(result.size()!=4){complete=false;break;}
            }
            List<Strategy> results=new ArrayList<>();for(var entry:rows.entrySet()){String[] key=entry.getKey().split(":");if(split.equals("all")||split.equals(key[1]))results.add(new Strategy(key[0],key[1],aggregate(entry.getValue()),List.copyOf(entry.getValue())));}
            boolean realReranker=results.stream().filter(s->s.mode().equals("rerank")).flatMap(s->s.queries().stream()).anyMatch(q->q.error()==null&&q.reranker().startsWith("http-rerank:"));
            String boundary=BOUNDARY+" 本次重排配置："+chosenReranker.identity()+"；真实重排成功执行："+realReranker+"；逐题降级与错误见结果。";
            verify(data);return new Report(data.version(),data.snapshotId(),data.modelIdentity(),real,selected.size(),complete,boundary,new Config(k,split,budget,1),cached.calls,cached.hits,elapsed(start),List.copyOf(results),data,chosenReranker.identity(),realReranker,rerankerCalls.get());
        }finally{busy.set(false);}
    }
    private List<QueryResult> evaluateQuestion(RetrievalService service,EvaluationDataset data,EvaluationDataset.Question question,int k,long deadline){
        List<QueryResult> result=new ArrayList<>();
        for(String mode:List.of("keyword","vector","hybrid","rerank")){
                long remaining=(deadline-System.nanoTime())/1_000_000;if(remaining<100||Thread.currentThread().isInterrupted())break;long phase=System.nanoTime();
                QueryResult row;
                try{var response=service.retrieve(new RetrievalService.Request(question.question(),mode.equals("rerank")?"hybrid":mode,k,20,mode.equals("rerank"),false,Math.min(10000,remaining)));
                    var ranking=response.hits().stream().map(RetrievalService.Hit::chunkId).toList();
                    Set<String> forbidden=new HashSet<>(question.forbidden());
                    for(var hit:response.hits()){var expected=data.chunks().get(hit.chunkId());
                        if(expected==null||!expected.active()||!expected.tenant().equals(EvaluationDataset.tenant(data.modelIdentity()))||!expected.documentId().equals(hit.documentId())||!expected.revisionId().equals(hit.revisionId()))forbidden.add(hit.chunkId());}
                    String error=response.degradations().isEmpty()?null:"DEGRADED: "+String.join(",",response.degradations());
                    row=new QueryResult(question.queryId(),question.question(),question.split(),ranking,EvaluationMetrics.score(question.labels(),forbidden,ranking,k),elapsed(phase),error,response.degradations(),response.reranker(),response.stages());
                }catch(RuntimeException failure){row=new QueryResult(question.queryId(),question.question(),question.split(),List.of(),null,elapsed(phase),"RETRIEVAL_FAILED",List.of(),"unavailable");}
                result.add(row);
        }return result;
    }
    static Metrics aggregate(List<QueryResult> rows){
        var valid=rows.stream().filter(q->q.score()!=null&&q.error()==null).toList();var answered=valid.stream().filter(q->!q.score().noAnswer()).toList();var noAnswer=valid.stream().filter(q->q.score().noAnswer()).toList();
        Map<String,List<Long>> stages=new LinkedHashMap<>();for(var row:rows)for(var stage:row.stages())stages.computeIfAbsent(stage.name(),ignored->new ArrayList<>()).add(stage.elapsedMs());
        Map<String,Latency> latency=new LinkedHashMap<>();stages.forEach((name,values)->{var sorted=values.stream().mapToLong(Long::longValue).sorted().toArray();latency.put(name,new Latency(percentile(sorted,.5),percentile(sorted,.95),sorted.length));});
        return new Metrics(answered.isEmpty()?null:answered.stream().mapToDouble(q->q.score().recall()).average().orElse(0),valid.stream().mapToDouble(q->q.score().mrr()).average().orElse(0),answered.isEmpty()?null:answered.stream().mapToDouble(q->q.score().ndcg()).average().orElse(0),noAnswer.size(),noAnswer.isEmpty()?null:(double)noAnswer.stream().filter(q->q.score().empty()).count()/noAnswer.size(),rows.stream().filter(q->q.score()!=null).mapToInt(q->q.score().forbiddenCount()).sum(),percentile(rows,.5),percentile(rows,.95),rows.size()-valid.size(),valid.size(),Map.copyOf(latency));
    }
    private static long percentile(List<QueryResult> rows,double p){return percentile(rows.stream().mapToLong(QueryResult::elapsedMs).sorted().toArray(),p);}
    private static long percentile(long[] times,double p){return times.length==0?0:times[Math.max(0,(int)Math.ceil(times.length*p)-1)];}
    private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
    static final class CachedEmbedding implements KnowledgeEmbedding {
        private final KnowledgeEmbedding delegate;private final Map<String,float[]> cache=new HashMap<>();int calls,hits;
        CachedEmbedding(KnowledgeEmbedding delegate){this.delegate=delegate;}
        public String identity(){return delegate.identity();}public int dimensions(){return delegate.dimensions();}
        public float[] embed(String text){return embed(text,Duration.ofSeconds(10));}
        public synchronized float[] embed(String text,Duration remaining){if(remaining.isZero()||remaining.isNegative()||Thread.currentThread().isInterrupted())throw new IllegalStateException("Evaluation deadline/cancellation");if(cache.containsKey(text)){hits++;return cache.get(text).clone();}calls++;var vector=delegate.embed(text,remaining);KnowledgeEmbedding.vectorLiteral(vector,dimensions());cache.put(text,vector.clone());return vector;}
    }
}
