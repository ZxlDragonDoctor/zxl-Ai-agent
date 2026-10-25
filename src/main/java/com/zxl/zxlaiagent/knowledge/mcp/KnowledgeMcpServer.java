package com.zxl.zxlaiagent.knowledge.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService;
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

/** MCP SDK 0.7.0 legacy SSE. One trusted local demonstration client; not Streamable HTTP or production authentication. */
public final class KnowledgeMcpServer {
    private KnowledgeMcpServer(){}
    @Configuration @EnableWebMvc @Profile("lesson15-mcp") public static class MvcConfiguration { }
    public static final class Running implements AutoCloseable {
        private final WebServer web;private final McpSyncServer mcp;private final KnowledgeLookupTool tool;private final AnnotationConfigWebApplicationContext context;private final SingleClientSseTransport transport;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Running(WebServer web,McpSyncServer mcp,KnowledgeLookupTool tool,AnnotationConfigWebApplicationContext context,SingleClientSseTransport transport){this.web=web;this.mcp=mcp;this.tool=tool;this.context=context;this.transport=transport;}
        public int port(){return web.getPort();}
        public int activeConnections(){return transport.activeConnections();}
        public int pendingRequests(){return transport.pendingRequests();}
        @Override public void close(){if(closed.compareAndSet(false,true)){try{mcp.close();}finally{try{web.stop();}finally{tool.close();context.close();}}}}
    }
    public static Running start(int port,KnowledgeLookupTool tool){
        if(port<0||port>65535)throw new IllegalArgumentException("Invalid port");
        var transport=new SingleClientSseTransport(new ObjectMapper());
        var context=new AnnotationConfigWebApplicationContext();context.getEnvironment().setActiveProfiles("lesson15-mcp");context.register(MvcConfiguration.class);
        context.addBeanFactoryPostProcessor(factory->factory.registerSingleton("mcpRouter",transport.getRouterFunction()));
        var mcp=McpServer.sync(transport).serverInfo("zxl-knowledge","1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).tool(KnowledgeLookupTool.definition(),tool::call).build();
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
    public static void main(String[] args){
        // Explicit environment only. Invalid/missing PG configuration prevents this service starting, never creates fake data.
        var db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        var embedding=new Lesson12Demo.DemoConfiguration().embedding();
        var retrieval=new RetrievalService(db,"demo-enterprise",embedding);
        int port=Integer.parseInt(System.getenv().getOrDefault("LESSON15_MCP_PORT","18135"));
        var server=start(port,new KnowledgeLookupTool(retrieval));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close,"knowledge-mcp-shutdown"));
        System.out.println("Knowledge MCP SDK=0.7.0 transport=SSE server=zxl-knowledge tool=knowledge_lookup_v1 loopback-port="+server.port());
    }
}
