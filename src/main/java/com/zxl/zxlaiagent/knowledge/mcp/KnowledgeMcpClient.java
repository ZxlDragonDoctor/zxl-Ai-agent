package com.zxl.zxlaiagent.knowledge.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.tools.support.StructuredMcpToolCallback;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Application-owned connection: fixed loopback destination, fixed allowlist, bounded queue and one shared deadline. */
public final class KnowledgeMcpClient implements AutoCloseable {
    public static final String SERVER_ID="knowledge",EXPOSED_NAME="knowledge__knowledge_lookup_v1";
    public record DiscoveredTool(String serverId,String originalName,String exposedName,String schemaVersion,String schemaSha256,McpSchema.Tool definition) { }
    public record Status(String state,String serverId,String endpoint,String protocolVersion,String lastError,int toolCount,int active,int queued) { }
    public static final class Failure extends IllegalStateException {
        private final String code;public Failure(String code){super(code);this.code=code;}public String code(){return code;}
    }
    private final URI endpoint;
    private final ThreadPoolExecutor workers;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Object lifecycle=new Object();
    // One trusted application owns the server's sole SSE connection; serialize discovery and tool exchanges.
    private final ReentrantLock exchange=new ReentrantLock();
    private volatile McpSyncClient session;
    private volatile String state="UNAVAILABLE",protocolVersion,lastError="NOT_DISCOVERED";
    private volatile List<DiscoveredTool> tools=List.of();
    public KnowledgeMcpClient(URI endpoint){
        if(endpoint==null||!"http".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())
                ||endpoint.getPort()<1||endpoint.getPort()>65535||endpoint.getRawUserInfo()!=null||endpoint.getRawQuery()!=null
                ||endpoint.getRawFragment()!=null||!(endpoint.getPath().isEmpty()||"/".equals(endpoint.getPath())))
            throw new IllegalArgumentException("Only an explicitly configured http://127.0.0.1:port MCP service is supported");
        this.endpoint=URI.create("http://127.0.0.1:"+endpoint.getPort());
        workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"knowledge-mcp-client");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public Status status(){return new Status(closed.get()?"CLOSED":state,SERVER_ID,endpoint.toString(),protocolVersion,lastError,tools.size(),workers.getActiveCount(),workers.getQueue().size());}
    public List<DiscoveredTool> tools(){return tools;}
    public Status refresh(Duration budget,BooleanSupplier cancelled){
        long end=deadline(budget);
        try{return bounded(()->locked(end,cancelled,()->{discard();connect(end,cancelled);return status();}),end,cancelled);}
        catch(Failure failure){if(!closed.get()){state="UNAVAILABLE";lastError=failure.code();}throw failure;}
    }
    public McpSchema.CallToolResult call(String exposedName,Map<String,Object> arguments,Duration budget,BooleanSupplier cancelled){
        if(!EXPOSED_NAME.equals(exposedName))throw new Failure("MCP_NOT_AUTHORIZED");
        try{KnowledgeLookupTool.validate(arguments);StructuredMcpToolCallback.validateInput(ToolResults.json(arguments),KnowledgeLookupTool.definition());}
        catch(RuntimeException invalid){throw new Failure("MCP_INVALID_ARGUMENT");}
        long end=deadline(budget);var original=Map.copyOf(arguments);
        return bounded(()->locked(end,cancelled,()->{
            for(int attempt=0;attempt<2;attempt++){
                try{
                    check(end,cancelled);if(session==null)connect(end,cancelled);check(end,cancelled);
                    long remaining=TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());
                    if(remaining<100)throw new Failure("MCP_DEADLINE_EXCEEDED");
                    var sent=new HashMap<String,Object>(original);sent.put("budgetMs",Math.min(10000,remaining));
                    var response=session.callTool(new McpSchema.CallToolRequest(KnowledgeLookupTool.NAME,sent));
                    check(end,cancelled); // A late result must never become a successful task completion.
                    if(response==null)throw new Failure("MCP_INVALID_RESPONSE");
                    return response;
                }catch(RuntimeException failure){
                    Failure classified=classify(failure);
                    if(!retryAllowed(true,classified.code(),attempt,remainingMillis(end)))throw classified;
                    discard();backoff(end,cancelled);
                }
            }
            throw new Failure("MCP_NETWORK_FAILURE");
        }),end,cancelled);
    }
    /** A write timeout has unknown outcome: policy never retries writes or arbitrary remote retryable text. */
    public static boolean retryAllowed(boolean readOnly,String code,int completedAttempts,long remainingMs){
        return readOnly&&completedAttempts==0&&remainingMs>250&&"MCP_NETWORK_FAILURE".equals(code);
    }
    private void connect(long end,BooleanSupplier cancelled){
        check(end,cancelled);state="CONNECTING";lastError=null;
        var transport=new HttpClientSseClientTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER),endpoint.toString(),new ObjectMapper());
        var candidate=McpClient.sync(transport).requestTimeout(Duration.ofMillis(Math.max(100,Math.min(10000,remainingMillis(end)))))
                .clientInfo(new McpSchema.Implementation("zxl-knowledge-client","1.0.0")).build();
        try{
            var initialized=candidate.initialize();check(end,cancelled);
            if(!"zxl-knowledge".equals(initialized.serverInfo().name())||!"1.0.0".equals(initialized.serverInfo().version()))throw new Failure("MCP_PROTOCOL_FAILURE");
            var discovered=candidate.listTools();check(end,cancelled);
            if(discovered==null||discovered.nextCursor()!=null||discovered.tools()==null||discovered.tools().size()!=1)throw new Failure("MCP_SCHEMA_CHANGED");
            var tool=discovered.tools().get(0);
            if(!KnowledgeLookupTool.NAME.equals(tool.name())||!Objects.equals(tool.inputSchema(),KnowledgeLookupTool.definition().inputSchema()))throw new Failure("MCP_SCHEMA_CHANGED");
            var approved=new McpSchema.Tool(EXPOSED_NAME,KnowledgeLookupTool.definition().description(),KnowledgeLookupTool.SCHEMA);
            var digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ToolResults.json(tool.inputSchema()).getBytes(StandardCharsets.UTF_8)));
            synchronized(lifecycle){
                check(end,cancelled);session=candidate;protocolVersion=initialized.protocolVersion();
                tools=List.of(new DiscoveredTool(SERVER_ID,tool.name(),EXPOSED_NAME,"v1",digest,approved));state="READY";lastError=null;
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
            if(Set.of("MCP_NETWORK_FAILURE","MCP_PROTOCOL_FAILURE","MCP_SCHEMA_CHANGED").contains(classified.code())){
                discard();if(!closed.get()){state="UNAVAILABLE";lastError=classified.code();}
            }throw classified;
        }finally{exchange.unlock();}
    }
    private void discard(){var previous=session;session=null;tools=List.of();if(previous!=null)previous.close();}
    private void backoff(long end,BooleanSupplier cancelled){
        for(int step=0;step<2;step++){check(end,cancelled);try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("MCP_CANCELLED");}}
    }
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
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.io.IOException)return new Failure("MCP_NETWORK_FAILURE");
        if(Thread.currentThread().isInterrupted())return new Failure("MCP_CANCELLED");
        return new Failure("MCP_PROTOCOL_FAILURE");
    }
    @Override public void close(){
        synchronized(lifecycle){if(closed.compareAndSet(false,true)){state="CLOSED";lastError="MCP_CLOSED";tools=List.of();workers.shutdownNow();
            // close is nonblocking in this SDK. Owned workers remain occupied until their actual transport work exits.
            discard();}}
    }
}
