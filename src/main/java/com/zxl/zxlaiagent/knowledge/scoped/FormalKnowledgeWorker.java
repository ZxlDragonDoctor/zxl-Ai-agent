package com.zxl.zxlaiagent.knowledge.scoped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.repository.FileConversationRepository;
import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.knowledge.answer.*;
import com.zxl.zxlaiagent.knowledge.retrieval.*;
import com.zxl.zxlaiagent.security.*;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.time.Clock;
import java.util.EnumSet;
/** Formal worker: explicitly registered scope-aware MVC only; no scan of teaching controllers. */
public final class FormalKnowledgeWorker {
 @Configuration @EnableWebMvc @Profile("lesson17-worker")
 public static class WorkerConfiguration {
  @Bean SecurityDatabase authority(){return SecurityDatabase.fromEnvironment();}
  @Bean AuthorizationService authorization(SecurityDatabase db){return new AuthorizationService(db);}
  @Bean ServiceCallAuthorizer tickets(SecurityDatabase db,AuthorizationService authorization){return new ServiceCallAuthorizer(db,authorization);}
  @Bean KnowledgeDatabase knowledgeDatabase(){return new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));}
  @Bean KnowledgeEmbedding embedding(){String mode=System.getenv("LESSON12_EMBEDDING_MODE");if("demo".equals(mode))return new DemoKnowledgeEmbedding();if(!"http".equals(mode))throw new IllegalArgumentException("Explicit LESSON12_EMBEDDING_MODE demo|http required");return new HttpKnowledgeEmbedding(URI.create(System.getenv("LESSON12_EMBEDDING_ENDPOINT")),System.getenv("LESSON12_EMBEDDING_API_KEY"),System.getenv("LESSON12_EMBEDDING_MODEL"),System.getenv("LESSON12_EMBEDDING_REVISION"),Integer.parseInt(System.getenv("LESSON12_EMBEDDING_DIMENSIONS")));}
  @Bean Reranker reranker(){String mode=System.getenv("LESSON13_RERANK_MODE");if("rule".equals(mode))return Reranker.rules();if(!"http".equals(mode))throw new IllegalArgumentException("Explicit LESSON13_RERANK_MODE rule|http required");return new HttpReranker(URI.create(System.getenv("LESSON13_RERANK_ENDPOINT")),System.getenv("LESSON13_RERANK_API_KEY"),System.getenv("LESSON13_RERANK_MODEL"),System.getenv("LESSON13_RERANK_REVISION"));}
  @Bean ScopedKnowledgeFactory scopes(KnowledgeDatabase db,AuthorizationService authorization,KnowledgeEmbedding embedding,Reranker reranker){String endpoint=System.getenv("LESSON14_CHAT_ENDPOINT");AnswerModel model=endpoint==null||endpoint.isBlank()?null:new HttpAnswerModel(endpoint,System.getenv("LESSON14_CHAT_API_KEY"),System.getenv("LESSON14_CHAT_MODEL"),System.getenv("LESSON14_JUDGE_MODEL"),System.getenv("LESSON14_CHAT_REVISION"));var mapper=new ObjectMapper().findAndRegisterModules();var repository=new FileConversationRepository(mapper,System.getenv().getOrDefault("LESSON17_CONVERSATION_DIR","runtime-data/conversations"));return new ScopedKnowledgeFactory(db,authorization,embedding,reranker,model,Clock.systemUTC(),repository);}
  @Bean ScopedKnowledgeController controller(ScopedKnowledgeFactory scopes){return new ScopedKnowledgeController(scopes);}
  @Bean WorkerAuthenticationFilter authentication(ServiceCallAuthorizer tickets,AuthorizationService authorization){return new WorkerAuthenticationFilter(tickets,authorization,System.getenv("LESSON17_INTERNAL_SERVICE_TOKEN"));}
 }
 public static void main(String[] args){var context=new AnnotationConfigWebApplicationContext();context.getEnvironment().setActiveProfiles("lesson17-worker");context.register(WorkerConfiguration.class);int port=Integer.parseInt(System.getenv().getOrDefault("LESSON17_WORKER_PORT","18132"));var factory=new TomcatServletWebServerFactory(port);factory.setAddress(java.net.InetAddress.getLoopbackAddress());var server=factory.getWebServer(servletContext->{context.setServletContext(servletContext);context.refresh();var filter=servletContext.addFilter("worker-authentication",context.getBean(WorkerAuthenticationFilter.class));filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST),false,"/*");var servlet=servletContext.addServlet("dispatcher",new DispatcherServlet(context));servlet.setLoadOnStartup(1);servlet.addMapping("/");});server.start();Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop();context.close();}));}
}
