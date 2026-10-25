package com.zxl.zxlaiagent.knowledge.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import org.springframework.web.servlet.function.*;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Bounded ownership around MCP SDK 0.7.0 JSON-RPC and Spring MVC SSE.
 * This deployment intentionally supports one trusted local application, never a tenant-multiplexed gateway.
 */
public final class SingleClientSseTransport implements ServerMcpTransport {
    private static final int MAX_PENDING=16;
    private record Connection(String id,ServerResponse.SseBuilder stream) { }
    private record Pending(Connection owner,Object originalId,long created) { }
    private final ObjectMapper mapper;
    private final AtomicReference<Connection> current=new AtomicReference<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Object ownership=new Object();
    private final Map<String,Pending> pending=new ConcurrentHashMap<>();
    private volatile Function<Mono<McpSchema.JSONRPCMessage>,Mono<McpSchema.JSONRPCMessage>> handler;
    private final RouterFunction<ServerResponse> router;

    public SingleClientSseTransport(ObjectMapper mapper){
        this.mapper=mapper;
        router=RouterFunctions.route().GET("/sse",this::open).POST("/mcp/message",this::post).build();
    }
    public RouterFunction<ServerResponse> getRouterFunction(){return router;}
    public int activeConnections(){return current.get()==null?0:1;}
    public int pendingRequests(){return pending.size();}
    @Override public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>,Mono<McpSchema.JSONRPCMessage>> handler){this.handler=handler;return Mono.empty();}
    private ServerResponse open(ServerRequest request){
        if(closed.get())return ServerResponse.status(503).build();
        return ServerResponse.sse(builder->{
            var connection=new Connection(UUID.randomUUID().toString(),builder);
            builder.onComplete(()->release(connection)).onError(error->release(connection)).onTimeout(()->release(connection));
            synchronized(ownership){
                if(closed.get()){builder.complete();return;}
                var previous=current.getAndSet(connection);
                if(previous!=null){pending.entrySet().removeIf(entry->entry.getValue().owner()==previous);previous.stream().complete();}
                try{synchronized(connection){builder.id(connection.id()).event("endpoint").data("/mcp/message?sessionId="+connection.id());}}
                catch(IOException|RuntimeException failure){release(connection);builder.error(failure);}
            }
        },Duration.ZERO);
    }
    private ServerResponse post(ServerRequest request){
        if(closed.get())return ServerResponse.status(503).build();
        Connection owner=current.get();String id=request.param("sessionId").orElse("");
        if(owner==null||!owner.id().equals(id))return ServerResponse.status(410).build();
        if(handler==null)return ServerResponse.status(503).build();
        String pendingId=null;
        try {
            var message=McpSchema.deserializeJsonRpcMessage(mapper,request.body(String.class));
            if(!"2.0".equals(message.jsonrpc()))return ServerResponse.badRequest().build();
            synchronized(ownership){
                if(closed.get()||current.get()!=owner)return ServerResponse.status(410).build();
                // Replace-session clears pending entries. A per-connection ceiling also bounds malformed/no-response requests.
                long now=System.nanoTime();pending.entrySet().removeIf(e->now-e.getValue().created()>Duration.ofSeconds(30).toNanos());
                if(message instanceof McpSchema.JSONRPCRequest rpc){
                    if(rpc.id()==null||!(rpc.id() instanceof String||rpc.id() instanceof Number)||rpc.id().toString().length()>200)return ServerResponse.badRequest().build();
                    if(pending.size()>=MAX_PENDING)return ServerResponse.status(429).build();
                    pendingId=owner.id()+"/"+UUID.randomUUID();
                    pending.put(pendingId,new Pending(owner,rpc.id(),now));
                    message=new McpSchema.JSONRPCRequest(rpc.jsonrpc(),rpc.method(),pendingId,rpc.params());
                }
            }
            final String routed=pendingId;
            handler.apply(Mono.just(message)).subscribe(ignored->{},error->{if(routed!=null)pending.remove(routed);});
            return ServerResponse.accepted().build();
        }catch(Exception invalid){if(pendingId!=null)pending.remove(pendingId);return ServerResponse.badRequest().build();}
    }
    private void release(Connection connection){
        current.compareAndSet(connection,null);
        pending.entrySet().removeIf(entry->entry.getValue().owner()==connection);
    }
    @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message){
        return Mono.fromRunnable(()->{
            if(closed.get())return;
            Connection target=current.get();McpSchema.JSONRPCMessage outgoing=message;
            if(message instanceof McpSchema.JSONRPCResponse response){
                if(response.id()==null)return;
                var route=pending.remove(response.id().toString());
                // Internal request ids are connection-unique. Results from a replaced connection are dropped, never broadcast.
                if(route==null||target!=route.owner())return;
                target=route.owner();outgoing=new McpSchema.JSONRPCResponse(response.jsonrpc(),route.originalId(),response.result(),response.error());
            }
            if(target==null)return;
            synchronized(target){
                if(current.get()!=target||closed.get())return;
                try{target.stream().id(target.id()).event("message").data(mapper.writeValueAsString(outgoing));}
                catch(IOException|RuntimeException failure){release(target);target.stream().error(failure);}
            }
        });
    }
    @Override public <T>T unmarshalFrom(Object data,TypeReference<T> type){return mapper.convertValue(data,type);}
    @Override public Mono<Void> closeGracefully(){return Mono.fromRunnable(()->{
        synchronized(ownership){
            if(closed.compareAndSet(false,true)){var connection=current.getAndSet(null);pending.clear();if(connection!=null)connection.stream().complete();}
        }
    });}
}
