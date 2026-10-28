package com.zxl.zxlaiagent.knowledge.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Formal v2 only. One authenticated shared SSE connection; independent call tickets bind actors. */
public final class FormalKnowledgeMcpServer {
    private FormalKnowledgeMcpServer(){}
    @Configuration @EnableWebMvc @Profile("lesson17-mcp") public static class MvcConfiguration { }
    public static final class Running implements AutoCloseable {
        private final WebServer web;private final McpSyncServer mcp;private final FormalKnowledgeMcpTool tool;private final AnnotationConfigWebApplicationContext context;private final SingleClientSseTransport transport;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Running(WebServer web,McpSyncServer mcp,FormalKnowledgeMcpTool tool,AnnotationConfigWebApplicationContext context,SingleClientSseTransport transport){this.web=web;this.mcp=mcp;this.tool=tool;this.context=context;this.transport=transport;}
        public int port(){return web.getPort();}
        public int activeConnections(){return transport.activeConnections();}
        public int pendingRequests(){return transport.pendingRequests();}
        @Override public void close(){if(closed.compareAndSet(false,true)){try{mcp.close();}finally{try{tool.close();}finally{try{web.stop();}finally{try{web.destroy();}finally{context.close();}}}}}}
    }
    public static Running start(int port,FormalKnowledgeMcpTool tool){return start(port,tool,System.getenv("LESSON17_INTERNAL_SERVICE_TOKEN"));}
    public static Running start(int port,FormalKnowledgeMcpTool tool,String internalToken){
        InternalServiceHttpClient.requireToken(internalToken);
        if(port<0||port>65535)throw new IllegalArgumentException("Invalid port");
        var transport=new SingleClientSseTransport(new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        var context=new AnnotationConfigWebApplicationContext();context.getEnvironment().setActiveProfiles("lesson17-mcp");context.register(MvcConfiguration.class);
        context.addBeanFactoryPostProcessor(factory->factory.registerSingleton("mcpRouter",transport.getRouterFunction()));
        var mcp=McpServer.sync(transport).serverInfo("zxl-knowledge-authorized","2.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).tool(FormalKnowledgeMcpProtocol.wireDefinition(),tool::call).build();
        var factory=new TomcatServletWebServerFactory(port);
        try{factory.setAddress(InetAddress.getByName("127.0.0.1"));}catch(Exception failure){throw new IllegalStateException(failure);}
        factory.addConnectorCustomizers(connector->{
            // The MCP SSE channel is application-owned and intentionally long-lived. Each JSON-RPC call retains its own deadline.
            connector.setAsyncTimeout(0);
            connector.setProperty("maxConnections","16");connector.setProperty("maxThreads","8");connector.setProperty("acceptCount","8");connector.setProperty("connectionTimeout","5000");
        });
        try {
            var web=factory.getWebServer(contextServlet->{
                context.setServletContext(contextServlet);context.refresh();
                var servlet=contextServlet.addServlet("mcp",new DispatcherServlet(context));servlet.setLoadOnStartup(1);servlet.setAsyncSupported(true);servlet.addMapping("/");
                var filter=contextServlet.addFilter("trusted-loopback-bounds",new Filter(){
                    @Override public void doFilter(ServletRequest raw,ServletResponse result,FilterChain chain)throws IOException,ServletException {
                        var request=(HttpServletRequest)raw;var response=(HttpServletResponse)result;
                        // SDK 0.7.0 creates a new Java HTTP client on every refresh and cannot close its idle pool on Java 17.
                        // Close each completed HTTP exchange; the SSE socket remains open until its explicit ownership ends.
                        response.setHeader("Connection","close");
                        // Java's fixed client sends no Origin. Reject browser-origin access, including hostile localhost pages.
                        if(!InternalServiceHttpClient.authorized(request.getHeader("Authorization"),internalToken)){response.setStatus(401);return;}
                        if(request.getHeader("Origin")!=null){response.sendError(403);return;}
                        if("POST".equals(request.getMethod())){
                            if(request.getContentLengthLong()>17408){response.sendError(413);return;}
                            byte[] bytes=request.getInputStream().readNBytes(17409);
                            if(bytes.length>17408){response.sendError(413);return;}
                            var wrapped=new HttpServletRequestWrapper(request){
                                @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8));}
                                @Override public ServletInputStream getInputStream(){
                                    var input=new ByteArrayInputStream(bytes);
                                    return new ServletInputStream(){
                                        public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
                                        public void setReadListener(ReadListener listener){throw new IllegalStateException("Synchronous bounded request body only");}
                                    };
                                }
                            };chain.doFilter(wrapped,response);
                        }else chain.doFilter(request,response);
                    }
                });filter.setAsyncSupported(true);filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST),false,"/*");
            });
            web.start();return new Running(web,mcp,tool,context,transport);
        }catch(RuntimeException failure){mcp.close();tool.close();context.close();throw failure;}
    }
}
