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
        @Bean com.zxl.zxlaiagent.knowledge.retrieval.Reranker reranker(){
            String mode=System.getenv().getOrDefault("LESSON13_RERANK_MODE","rule");
            if("rule".equals(mode))return com.zxl.zxlaiagent.knowledge.retrieval.Reranker.rules();
            if(!"http".equals(mode))throw new IllegalArgumentException("LESSON13_RERANK_MODE must be rule or http");
            String endpoint=System.getenv("LESSON13_RERANK_ENDPOINT");
            if(endpoint==null||!endpoint.startsWith("https://"))throw new IllegalArgumentException("HTTP reranking requires explicit HTTPS LESSON13_RERANK_ENDPOINT");
            return new com.zxl.zxlaiagent.knowledge.retrieval.HttpReranker(java.net.URI.create(endpoint),
                    System.getenv("LESSON13_RERANK_API_KEY"),System.getenv("LESSON13_RERANK_MODEL"),System.getenv("LESSON13_RERANK_REVISION"));
        }
        @Bean com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService retrievalService(KnowledgeDatabase db,KnowledgeEmbedding embedding,com.zxl.zxlaiagent.knowledge.retrieval.Reranker reranker){return new com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService(db,"demo-enterprise",embedding,reranker);}
        @Bean com.zxl.zxlaiagent.knowledge.retrieval.RetrievalController retrievalController(com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService service){return new com.zxl.zxlaiagent.knowledge.retrieval.RetrievalController(service);}
        @Bean com.zxl.zxlaiagent.knowledge.evaluation.EvaluationService evaluationService(KnowledgeDatabase db, KnowledgeEmbedding embedding, com.zxl.zxlaiagent.knowledge.retrieval.Reranker reranker) {
            return new com.zxl.zxlaiagent.knowledge.evaluation.EvaluationService(db, embedding, reranker);
        }
        @Bean com.zxl.zxlaiagent.knowledge.evaluation.EvaluationController evaluationController(com.zxl.zxlaiagent.knowledge.evaluation.EvaluationService service) {
            return new com.zxl.zxlaiagent.knowledge.evaluation.EvaluationController(service);
        }
        @Bean com.zxl.zxlaiagent.knowledge.memory.MemoryController memoryController(KnowledgeDatabase db) {
            return new com.zxl.zxlaiagent.knowledge.memory.MemoryController(db);
        }
        @Bean com.zxl.zxlaiagent.knowledge.answer.MemoryContext memoryContext(KnowledgeDatabase db) {
            return new com.zxl.zxlaiagent.knowledge.memory.MemoryAnswerContext(db, java.time.Clock.systemUTC());
        }
        @Bean com.zxl.zxlaiagent.knowledge.answer.AnswerService answerService(KnowledgeDatabase db,
                com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService retrieval,
                com.zxl.zxlaiagent.knowledge.answer.MemoryContext memory) {
            com.zxl.zxlaiagent.knowledge.answer.AnswerModel model = null;
            String endpoint = System.getenv("LESSON14_CHAT_ENDPOINT");
            if (endpoint != null && !endpoint.isBlank()) model = new com.zxl.zxlaiagent.knowledge.answer.HttpAnswerModel(
                    endpoint, System.getenv("LESSON14_CHAT_API_KEY"),
                    System.getenv("LESSON14_CHAT_MODEL"), System.getenv("LESSON14_JUDGE_MODEL"), System.getenv("LESSON14_CHAT_REVISION"));
            return new com.zxl.zxlaiagent.knowledge.answer.AnswerService(db, "demo-enterprise", retrieval, model, memory);
        }
        @Bean com.zxl.zxlaiagent.knowledge.answer.AnswerController answerController(com.zxl.zxlaiagent.knowledge.answer.AnswerService service) {
            return new com.zxl.zxlaiagent.knowledge.answer.AnswerController(service);
        }
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
