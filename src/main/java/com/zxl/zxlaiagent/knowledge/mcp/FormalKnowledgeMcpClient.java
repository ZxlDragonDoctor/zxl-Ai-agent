package com.zxl.zxlaiagent.knowledge.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Application-owned connection: fixed loopback destination, fixed allowlist, bounded queue and one shared deadline. */
public final class FormalKnowledgeMcpClient implements AutoCloseable {
    public static final String SERVER_ID="knowledge",EXPOSED_NAME=FormalKnowledgeMcpProtocol.EXPOSED_NAME;
    public record DiscoveredTool(String serverId,String originalName,String exposedName,String schemaVersion,String schemaSha256,String wireSchemaSha256,McpSchema.Tool definition) { }
    public record Status(String state,String serverId,String endpoint,String protocolVersion,String lastError,int toolCount,int active,int queued) { }
    public static final class Failure extends IllegalStateException {
        private final String code;public Failure(String code){super(code);this.code=code;}public String code(){return code;}
    }
    private final URI endpoint;
    private final String internalToken;private final ServiceCallAuthorizer tickets;private final AuthorizationService authorization;
    private final long sdkMaxRequestTimeoutMillis;
    private final ThreadPoolExecutor workers;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Object lifecycle=new Object();
    // One trusted application owns the server's sole SSE connection; serialize discovery and tool exchanges.
    private final ReentrantLock exchange=new ReentrantLock();
    private volatile McpSyncClient session;
    private volatile long sessionRequestTimeoutMillis;
    private volatile String state="UNAVAILABLE",protocolVersion,lastError="NOT_DISCOVERED";
    private volatile List<DiscoveredTool> tools=List.of();
    public FormalKnowledgeMcpClient(URI endpoint,String internalToken,ServiceCallAuthorizer tickets,AuthorizationService authorization){this(endpoint,internalToken,tickets,authorization,Duration.ofSeconds(10));}
    /** Package-level transport-test seam: discovery deadline and per-RPC SDK timeout are distinct. */
    FormalKnowledgeMcpClient(URI endpoint,String internalToken,ServiceCallAuthorizer tickets,AuthorizationService authorization,Duration sdkMaxRequestTimeout){
        InternalServiceHttpClient.requireToken(internalToken);this.internalToken=internalToken;this.tickets=Objects.requireNonNull(tickets);this.authorization=Objects.requireNonNull(authorization);
        if(sdkMaxRequestTimeout==null||sdkMaxRequestTimeout.compareTo(Duration.ofMillis(100))<0||sdkMaxRequestTimeout.compareTo(Duration.ofSeconds(10))>0)
            throw new IllegalArgumentException("MCP SDK request timeout must be 100..10000 ms");
        if(endpoint==null||!"http".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())
                ||endpoint.getPort()<1||endpoint.getPort()>65535||endpoint.getRawUserInfo()!=null||endpoint.getRawQuery()!=null
                ||endpoint.getRawFragment()!=null||!(endpoint.getPath().isEmpty()||"/".equals(endpoint.getPath())))
            throw new IllegalArgumentException("Only an explicitly configured http://127.0.0.1:port MCP service is supported");
        this.endpoint=URI.create("http://127.0.0.1:"+endpoint.getPort());
        this.sdkMaxRequestTimeoutMillis=sdkMaxRequestTimeout.toMillis();
        workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"knowledge-mcp-client");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public Status status(){return new Status(closed.get()?"CLOSED":state,SERVER_ID,endpoint.toString(),protocolVersion,lastError,tools.size(),workers.getActiveCount(),workers.getQueue().size());}
    public List<DiscoveredTool> tools(){return tools;}
    /** Return the discovered immutable directory while owning the exchange lock. A later refresh
     * may clear the live directory, but must not change a caller's already selected snapshot. */
    public List<DiscoveredTool> discover(Duration budget,BooleanSupplier cancelled){
        long end=deadline(budget);
        return bounded(()->locked(end,cancelled,()->{
            check(end,cancelled);if(session==null)connect(end,cancelled);check(end,cancelled);
            return List.copyOf(tools);
        }),end,cancelled);
    }
    public Status refresh(Duration budget,BooleanSupplier cancelled){
        long end=deadline(budget);
        try{return bounded(()->locked(end,cancelled,()->{discard();connect(end,cancelled);return status();}),end,cancelled);}
        catch(Failure failure){if(!closed.get()){state="UNAVAILABLE";lastError=failure.code();}throw failure;}
    }
    public McpSchema.CallToolResult call(ActorRef actor,String exposedName,Map<String,Object> arguments,Duration budget,BooleanSupplier cancelled){
        if(!EXPOSED_NAME.equals(exposedName))throw new Failure("MCP_NOT_AUTHORIZED");
        final Map<String,Object> original;
        try{original=FormalKnowledgeMcpProtocol.modelArguments(arguments);}
        catch(RuntimeException invalid){throw new Failure("MCP_INVALID_ARGUMENT");}
        long end=deadline(budget);
        return bounded(()->locked(end,cancelled,()->{
            check(end,cancelled);authorization.decide(actor,FormalKnowledgeMcpProtocol.ACTION,null,Phase.DEQUEUE,null).requireAllowed();
            if(session==null)connect(end,cancelled);check(end,cancelled);
            long responseHeadroom=Math.max(100,Math.min(500,sessionRequestTimeoutMillis/4));
            long serverBudget=Math.min(10000,Math.min(remainingMillis(end),sessionRequestTimeoutMillis))-responseHeadroom;
            if(serverBudget<100)throw new Failure("MCP_DEADLINE_EXCEEDED");
            var business=new HashMap<String,Object>(original);business.put("budgetMs",serverBudget);
            var sent=new HashMap<String,Object>(business);
            sent.put("authorization",tickets.issue(actor,FormalKnowledgeMcpProtocol.AUDIENCE,FormalKnowledgeMcpProtocol.ACTION,null,FormalKnowledgeMcpProtocol.requestHash(business)));
            check(end,cancelled);
            // No replay/retry: even a lost response may mean this one-use ticket was consumed.
            var response=session.callTool(new McpSchema.CallToolRequest(FormalKnowledgeMcpProtocol.NAME,sent));
            check(end,cancelled);
            if(response==null)throw new Failure("MCP_INVALID_RESPONSE");
            return authorization.gate(actor,FormalKnowledgeMcpProtocol.ACTION,null,Phase.RESULT_PUBLISH,null,()->response);
        }),end,cancelled);
    }
    private void connect(long end,BooleanSupplier cancelled){
        check(end,cancelled);state="CONNECTING";lastError=null;
        var transport=new HttpClientSseClientTransport(InternalServiceHttpClient.builder(internalToken,endpoint),endpoint.toString(),new ObjectMapper());
        long requestTimeoutMillis=Math.max(100,Math.min(sdkMaxRequestTimeoutMillis,remainingMillis(end)));
        var candidate=McpClient.sync(transport).requestTimeout(Duration.ofMillis(requestTimeoutMillis))
                .clientInfo(new McpSchema.Implementation("zxl-authorized-knowledge-client","2.0.0")).build();
        try{
            var initialized=candidate.initialize();check(end,cancelled);
            if(!"zxl-knowledge-authorized".equals(initialized.serverInfo().name())||!"2.0.0".equals(initialized.serverInfo().version()))throw new Failure("MCP_PROTOCOL_FAILURE");
            var discovered=candidate.listTools();check(end,cancelled);
            if(discovered==null||discovered.nextCursor()!=null||discovered.tools()==null||discovered.tools().size()!=1)throw new Failure("MCP_SCHEMA_CHANGED");
            var tool=discovered.tools().get(0);
            if(!FormalKnowledgeMcpProtocol.NAME.equals(tool.name())||!Objects.equals(tool.inputSchema(),FormalKnowledgeMcpProtocol.wireDefinition().inputSchema()))throw new Failure("MCP_SCHEMA_CHANGED");
            var approved=FormalKnowledgeMcpProtocol.modelDefinition();
            var digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ToolResults.json(tool.inputSchema()).getBytes(StandardCharsets.UTF_8)));
            synchronized(lifecycle){
                check(end,cancelled);session=candidate;sessionRequestTimeoutMillis=requestTimeoutMillis;protocolVersion=initialized.protocolVersion();
                tools=List.of(new DiscoveredTool(SERVER_ID,tool.name(),EXPOSED_NAME,"v2",FormalKnowledgeMcpProtocol.schemaDigest(FormalKnowledgeMcpProtocol.MODEL_SCHEMA),digest,approved));state="READY";lastError=null;
            }
        }catch(Exception failure){candidate.close();tools=List.of();if(!closed.get()){state="UNAVAILABLE";lastError=classify(failure).code();}throw classify(failure);}
    }
    private <T>T bounded(Callable<T> task,long end,BooleanSupplier cancelled){
        check(end,cancelled);final Future<T> future;
        try{future=workers.submit(task);}catch(RejectedExecutionException full){throw new Failure(closed.get()?"MCP_CLOSED":"MCP_CAPACITY");}
        try{
            while(true){check(end,cancelled);try{T result=future.get(Math.min(25,Math.max(1,remainingMillis(end))),TimeUnit.MILLISECONDS);check(end,cancelled);return result;}
                catch(TimeoutException poll){/* original deadline is retained, including queue wait */}}
        }catch(ExecutionException failure){throw classify(failure.getCause());}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Failure("MCP_CANCELLED");}
        finally{future.cancel(true);workers.purge();}
    }
    private <T>T locked(long end,BooleanSupplier cancelled,Callable<T> task)throws Exception {
        while(true){check(end,cancelled);if(exchange.tryLock(Math.min(25,Math.max(1,remainingMillis(end))),TimeUnit.MILLISECONDS))break;}
        try{return task.call();}catch(RuntimeException failure){
            var classified=classify(failure);
            if(Set.of("MCP_NETWORK_FAILURE","MCP_PROTOCOL_FAILURE","MCP_SCHEMA_CHANGED","MCP_DEADLINE_EXCEEDED","MCP_CANCELLED").contains(classified.code())){
                discard();if(!closed.get()){state="UNAVAILABLE";lastError=classified.code();}
            }throw classified;
        }finally{exchange.unlock();}
    }
    private void discard(){var previous=session;session=null;sessionRequestTimeoutMillis=0;tools=List.of();if(previous!=null)previous.close();}
    private void check(long end,BooleanSupplier cancelled){
        if(closed.get())throw new Failure("MCP_CLOSED");
        if(Thread.currentThread().isInterrupted()||cancelled!=null&&cancelled.getAsBoolean())throw new Failure("MCP_CANCELLED");
        if(System.nanoTime()>=end)throw new Failure("MCP_DEADLINE_EXCEEDED");
    }
    private long deadline(Duration budget){
        if(closed.get())throw new Failure("MCP_CLOSED");
        if(budget==null||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofSeconds(30))>0)throw new Failure("MCP_INVALID_ARGUMENT");
        return System.nanoTime()+budget.toNanos();
    }
    private static long remainingMillis(long end){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime()));}
    private static Failure classify(Throwable failure){
        if(failure instanceof Failure typed)return typed;
        if(failure instanceof SecurityFailure)return new Failure("MCP_NOT_AUTHORIZED");
        if(Thread.currentThread().isInterrupted())return new Failure("MCP_CANCELLED");
        var causes=new ArrayList<Throwable>();var visited=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null&&causes.size()<16&&visited.add(cause);cause=cause.getCause())causes.add(cause);
        for(var cause:causes)if(cause instanceof Failure typed)return typed;
        for(var cause:causes)if(cause instanceof InterruptedException||cause instanceof CancellationException||reactor.core.Exceptions.isCancel(cause))return new Failure("MCP_CANCELLED");
        // Mono.timeout (used by this SDK) is a causal TimeoutException, even when Reactor wraps it.
        // HttpTimeoutException is also IOException, so timeout detection must precede network detection.
        for(var cause:causes)if(cause instanceof TimeoutException||cause instanceof java.net.http.HttpTimeoutException||cause instanceof java.net.SocketTimeoutException)return new Failure("MCP_DEADLINE_EXCEEDED");
        for(var cause:causes)if(cause instanceof java.io.IOException)return new Failure("MCP_NETWORK_FAILURE");
        return new Failure("MCP_PROTOCOL_FAILURE");
    }
    @Override public void close(){
        synchronized(lifecycle){if(closed.compareAndSet(false,true)){state="CLOSED";lastError="MCP_CLOSED";tools=List.of();workers.shutdownNow();
            // close is nonblocking in this SDK. Owned workers remain occupied until their actual transport work exits.
            discard();}}
    }
}
