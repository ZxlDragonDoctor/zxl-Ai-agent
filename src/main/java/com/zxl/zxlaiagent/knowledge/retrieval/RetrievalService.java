package com.zxl.zxlaiagent.knowledge.retrieval;

import com.zxl.zxlaiagent.knowledge.*;
import java.sql.*;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Retrieval never accepts a client supplied tenant. Construct with a trusted server scope. */
public final class RetrievalService {
    public record Request(String query,String mode,int topK,int candidateLimit,boolean rerank,boolean rewrite,long budgetMs) { }
    public record Hit(String chunkId,String documentId,String revisionId,long generation,String text,
                      int startLine,int endLine,int startUtf16,int endUtf16,Double distance,Integer vectorRank,
                      Integer keywordRank,double fusionScore,Double rerankScore,List<String> channels) { }
    public record Stage(String name,String status,long elapsedMs,int candidateCount) { }
    public record Response(String traceId,String query,String rewrittenQuery,String mode,String embeddingMode,
                           String reranker,long elapsedMs,List<Stage> stages,List<String> degradations,String shortageReason,List<Hit> hits) { }
    public static final class Failure extends IllegalStateException {
        private final String code;
        public Failure(String code) { super(code); this.code=code; }
        public String code() { return code; }
    }
    private final KnowledgeDatabase db;
    private final String tenant;
    private final KnowledgeEmbedding embedding;
    private final Reranker reranker;
    private static final ThreadPoolExecutor RERANK_WORKERS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),task->{var thread=new Thread(task,"retrieval-rerank");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    public RetrievalService(KnowledgeDatabase db,String tenant,KnowledgeEmbedding embedding) { this(db,tenant,embedding,Reranker.rules()); }
    public RetrievalService(KnowledgeDatabase db,String tenant,KnowledgeEmbedding embedding,Reranker reranker) {
        this.db=Objects.requireNonNull(db); this.tenant=Objects.requireNonNull(tenant);
        this.embedding=Objects.requireNonNull(embedding); this.reranker=Objects.requireNonNull(reranker);
    }
    public Response retrieve(Request request) {
        validate(request);
        long started=System.nanoTime(); var deadline=new RetrievalDeadline(request.budgetMs());
        List<Stage> stages=new ArrayList<>(); List<String> degradations=new ArrayList<>();
        long profileStarted=System.nanoTime();
        profileReady(deadline);
        stages.add(stage("index-profile","OK",profileStarted,0));
        String rewritten=request.rewrite()?QueryTerms.supplement(request.query()):null;
        stages.add(new Stage("rewrite",request.rewrite()?(rewritten==null?"UNCHANGED":"DETERMINISTIC_LEXICAL_SUPPLEMENT"):"DISABLED",0,0));
        String lexical=rewritten==null?request.query():rewritten;
        List<Hit> vector=List.of(), keyword=List.of(); int successful=0;
        if (!"keyword".equals(request.mode())) {
            long phase=System.nanoTime();
            try {
                // Original semantic query is always retained; supplement only enriches the keyword route.
                String literal=KnowledgeEmbedding.vectorLiteral(embedding.embed(request.query(),deadline.remaining()),embedding.dimensions());
                vector=vector(literal,request.candidateLimit(),deadline); successful++;
                stages.add(stage("vector","OK",phase,vector.size()));
            } catch (RuntimeException failure) {
                propagateCancellation(failure); degradations.add("VECTOR_CHANNEL_FAILED"); stages.add(stage("vector","FAILED",phase,0));
            }
        }
        if (!"vector".equals(request.mode())) {
            long phase=System.nanoTime();
            try { keyword=keyword(lexical,request.candidateLimit(),deadline); successful++; stages.add(stage("keyword","OK",phase,keyword.size())); }
            catch (RuntimeException failure) { propagateCancellation(failure); degradations.add("KEYWORD_CHANNEL_FAILED"); stages.add(stage("keyword","FAILED",phase,0)); }
        }
        if(successful==0) throw new Failure("ALL_RETRIEVAL_CHANNELS_FAILED");
        var candidates=RankFusion.fuse(vector,keyword,request.candidateLimit());
        long phase=System.nanoTime();
        // Check before any candidate may cross the reranker boundary.
        candidates=current(candidates,deadline); stages.add(stage("authorization-before-rerank","OK",phase,candidates.size()));
        String usedReranker="disabled";
        if(request.rerank() && !candidates.isEmpty()) {
            phase=System.nanoTime();
            try {
                var scores=Reranker.validate(candidates,boundedRerank(request.query(),List.copyOf(candidates),deadline));
                deadline.remaining();
                candidates=candidates.stream().map(h->withScore(h,scores.get(h.chunkId())))
                        .sorted(Comparator.comparingDouble((Hit h)->h.rerankScore()).reversed().thenComparing(Comparator.comparingDouble(Hit::fusionScore).reversed()).thenComparing(Hit::chunkId)).toList();
                usedReranker=reranker.identity(); stages.add(stage("rerank","OK",phase,candidates.size()));
            } catch(RuntimeException failure) {
                propagateCancellation(failure); degradations.add("RERANK_FAILED_OR_BUDGET_EXHAUSTED"); usedReranker="fallback-rrf"; stages.add(stage("rerank","DEGRADED",phase,candidates.size()));
            }
        }
        phase=System.nanoTime(); candidates=current(candidates,deadline);
        stages.add(stage("final-visibility","OK",phase,candidates.size()));
        List<Hit> result=candidates.stream().limit(request.topK()).toList();
        String shortage=result.size()<request.topK()?(result.isEmpty()?"NO_CURRENT_MATCHES":"INSUFFICIENT_CURRENT_CANDIDATES"):null;
        return new Response(UUID.randomUUID().toString(),request.query(),rewritten,request.mode(),embedding.identity(),usedReranker,
                elapsed(started),List.copyOf(stages),List.copyOf(degradations),shortage,result);
    }
    private static void propagateCancellation(RuntimeException failure) {
        if(Thread.currentThread().isInterrupted() || failure instanceof RetrievalDeadline.Cancelled) throw new RetrievalDeadline.Cancelled();
    }
    private List<Reranker.Score> boundedRerank(String query,List<Hit> candidates,RetrievalDeadline deadline) {
        long remaining=deadline.remaining().toMillis();
        // Reserve database revalidation time. A hard deadline still fails the whole request.
        long optional=remaining-Math.max(250,remaining/4);
        if(optional<10)throw new IllegalStateException("Insufficient optional rerank budget");
        Future<List<Reranker.Score>> future=RERANK_WORKERS.submit(()->{
            // Queue time is a visibility window: check at actual dispatch, not only at submission.
            var authorized=current(candidates,deadline);
            if(!authorized.equals(candidates))throw new IllegalStateException("Rerank candidates changed while queued");
            long actualRemaining=deadline.remaining().toMillis();
            long httpBudget=actualRemaining-Math.max(250,actualRemaining/4);
            if(httpBudget<10)throw new IllegalStateException("Insufficient rerank budget after queue and authorization");
            return reranker.rank(query,authorized,Duration.ofMillis(httpBudget));
        });
        try { return future.get(optional,TimeUnit.MILLISECONDS); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new RetrievalDeadline.Cancelled(); }
        catch(ExecutionException | TimeoutException failure) { throw new IllegalStateException("Reranker failed or timed out",failure); }
        finally { future.cancel(true); RERANK_WORKERS.purge(); }
    }
    public static void validate(Request r) {
        if(r==null || r.query()==null || r.query().isBlank() || r.query().length()>2000 || !Set.of("vector","keyword","hybrid").contains(r.mode()==null?"":r.mode())
                || r.topK()<1 || r.topK()>5 || r.candidateLimit()<r.topK() || r.candidateLimit()>20 || r.budgetMs()<100 || r.budgetMs()>10000)
            throw new IllegalArgumentException("query 1..2000, mode vector|keyword|hybrid, topK 1..5, candidateLimit topK..20, budgetMs 100..10000");
    }
    private static final String ACTIVE=" FROM knowledge_chunk k JOIN knowledge_document d ON d.tenant_id=k.tenant_id AND d.document_id=k.document_id AND d.active_revision=k.revision_id JOIN knowledge_revision r ON r.tenant_id=k.tenant_id AND r.document_id=k.document_id AND r.revision_id=k.revision_id WHERE k.tenant_id=? AND k.model_identity=? AND k.dimensions=? AND r.model_identity=k.model_identity AND r.state='ACTIVE' AND d.status='ACTIVE' ";
    private void profileReady(RetrievalDeadline deadline) {
        db.tx(deadline.remaining(),connection->{
            try(var statement=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM knowledge_document WHERE tenant_id=? AND status='ACTIVE'), EXISTS(SELECT 1"+ACTIVE+")")){
                statement.setString(1,tenant);scope(statement,2);
                try(var row=statement.executeQuery()){row.next();if(row.getBoolean(1)&&!row.getBoolean(2))throw new Failure("NO_COMPATIBLE_INDEX_PROFILE");}
            }return null;
        });
    }
    private List<Hit> vector(String literal,int limit,RetrievalDeadline deadline) {
        return db.tx(deadline.remaining(),c->{
            try(var s=c.prepareStatement("SELECT k.*,d.version,k.embedding <=> ?::vector AS distance"+ACTIVE+"ORDER BY distance,k.chunk_id LIMIT ?")) {
                s.setString(1,literal); scope(s,2); s.setInt(5,limit);
                try(var rows=s.executeQuery()) { return read(rows,true); }
            }
        });
    }
    private List<Hit> keyword(String query,int limit,RetrievalDeadline deadline) {
        var terms=QueryTerms.extract(query);
        // Even an un-tokenizable query must observe storage availability. A broken
        // database must never look like a successful empty keyword search.
        if(terms.isEmpty())return db.tx(deadline.remaining(),connection->List.of());
        // position uses bind parameters: %, _, quotes and SQL syntax remain literal text.
        String score=String.join(" + ",Collections.nCopies(terms.size(),"CASE WHEN position(? in lower(k.text_content))>0 THEN 1 ELSE 0 END"));
        return db.tx(deadline.remaining(),c->{
            String sql="SELECT * FROM (SELECT k.*,d.version,("+score+") AS keyword_score"+ACTIVE+") scored WHERE keyword_score>0 ORDER BY keyword_score DESC,chunk_id LIMIT ?";
            try(var s=c.prepareStatement(sql)) {
                int index=1; for(String term:terms)s.setString(index++,term);
                scope(s,index); s.setInt(index+3,limit);
                try(var rows=s.executeQuery()) { return read(rows,false); }
            }
        });
    }
    private void scope(PreparedStatement s,int offset)throws SQLException {
        s.setString(offset,tenant); s.setString(offset+1,embedding.identity()); s.setInt(offset+2,embedding.dimensions());
    }
    private List<Hit> read(ResultSet rows,boolean vector)throws SQLException {
        List<Hit> result=new ArrayList<>();
        while(rows.next())result.add(new Hit(rows.getString("chunk_id"),rows.getString("document_id"),rows.getString("revision_id"),rows.getLong("version"),rows.getString("text_content"),rows.getInt("start_line"),rows.getInt("end_line"),rows.getInt("start_utf16"),rows.getInt("end_utf16"),vector?rows.getDouble("distance"):null,null,null,0,null,List.of()));
        return result;
    }
    private List<Hit> current(List<Hit> candidates,RetrievalDeadline deadline) {
        deadline.remaining(); if(candidates.isEmpty())return candidates;
        return db.tx(deadline.remaining(),c->{
            List<Hit> current=new ArrayList<>();
            String placeholders=String.join(",",Collections.nCopies(candidates.size(),"?"));
            try(var s=c.prepareStatement("SELECT k.chunk_id,d.version"+ACTIVE+"AND k.chunk_id IN ("+placeholders+")")) {
                scope(s,1); int index=4; for(var h:candidates)s.setString(index++,h.chunkId());
                Map<String,Long> visible=new HashMap<>();
                try(var rows=s.executeQuery()) { while(rows.next())visible.put(rows.getString(1),rows.getLong(2)); }
                for(var h:candidates)if(Objects.equals(visible.get(h.chunkId()),h.generation()))current.add(h);
            }
            return List.copyOf(current);
        });
    }
    private static Hit withScore(Hit h,double score) { return new Hit(h.chunkId(),h.documentId(),h.revisionId(),h.generation(),h.text(),h.startLine(),h.endLine(),h.startUtf16(),h.endUtf16(),h.distance(),h.vectorRank(),h.keywordRank(),h.fusionScore(),score,h.channels()); }
    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
    private static Stage stage(String name,String status,long start,int count) { return new Stage(name,status,elapsed(start),count); }
}
