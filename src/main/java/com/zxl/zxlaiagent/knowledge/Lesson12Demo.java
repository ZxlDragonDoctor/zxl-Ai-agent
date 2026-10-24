package com.zxl.zxlaiagent.knowledge;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Narrow MVC startup: no component scan, no model/RAG/MCP initialization, no paid API calls. */
public final class Lesson12Demo {
    @Configuration @EnableWebMvc @Profile("lesson12-demo")
    public static class DemoConfiguration {
        @Bean KnowledgeDatabase database(){return new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));}
        @Bean KnowledgeEmbedding embedding(){
            String mode=System.getenv().getOrDefault("LESSON12_EMBEDDING_MODE","demo");
            if("demo".equals(mode))return new DemoKnowledgeEmbedding();
            if(!"http".equals(mode))throw new IllegalArgumentException("LESSON12_EMBEDDING_MODE must be demo or http");
            String endpoint=System.getenv("LESSON12_EMBEDDING_ENDPOINT");
            if(endpoint==null||!endpoint.startsWith("https://"))throw new IllegalArgumentException("Real embedding mode requires explicit HTTPS LESSON12_EMBEDDING_ENDPOINT");
            return new HttpKnowledgeEmbedding(java.net.URI.create(endpoint),System.getenv("LESSON12_EMBEDDING_API_KEY"),
                    System.getenv().getOrDefault("LESSON12_EMBEDDING_MODEL","text-embedding-v3"),System.getenv("LESSON12_EMBEDDING_REVISION"),
                    Integer.parseInt(System.getenv().getOrDefault("LESSON12_EMBEDDING_DIMENSIONS","1024")));
        }
        @Bean KnowledgeService service(KnowledgeDatabase db,KnowledgeEmbedding embedding){return new KnowledgeService(db,"demo-enterprise",embedding);}
        @Bean KnowledgeController controller(KnowledgeService service){return new KnowledgeController(service);}
    }
    public static void main(String[] args){
        var context=new AnnotationConfigWebApplicationContext();context.getEnvironment().setActiveProfiles("lesson12-demo");context.register(DemoConfiguration.class);
        int port=Integer.parseInt(System.getenv().getOrDefault("LESSON12_PORT","18132"));
        var factory=new TomcatServletWebServerFactory(port);
        factory.setAddress(java.net.InetAddress.getLoopbackAddress());
        var server=factory.getWebServer(servletContext->{context.setServletContext(servletContext);context.refresh();var servlet=servletContext.addServlet("dispatcher",new DispatcherServlet(context));servlet.setLoadOnStartup(1);servlet.addMapping("/");});
        server.start();Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop();context.close();}));
    }
}
