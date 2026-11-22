package com.zxl.enterprise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.ai.tool.ToolCallback;
import com.zxl.zxlaiagent.research.ResearchRunner;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import com.fasterxml.jackson.databind.JsonNode;

/** Explicit formal deployment entry point; the classroom application's scan is not imported. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages={"com.zxl.zxlaiagent.conversation","com.zxl.zxlaiagent.multiagent",
 "com.zxl.zxlaiagent.security","com.zxl.zxlaiagent.skills","com.zxl.zxlaiagent.usage",
 "com.zxl.zxlaiagent.research","com.zxl.zxlaiagent.ticket","com.zxl.zxlaiagent.knowledge.gateway",
 "com.zxl.zxlaiagent.agent.stream"},excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern="com\\.zxl\\.zxlaiagent\\.research\\.ResearchConfiguration"))
public class EnterpriseAgentApplication {
 public static void main(String[] args) {
  var application=new SpringApplication(EnterpriseAgentApplication.class);
  application.addInitializers(context->validate(context.getEnvironment()));
  application.run(args);
 }
 public static void validate(Environment environment) {
  var profiles=java.util.Set.of(environment.getActiveProfiles());
  if(!profiles.contains("enterprise") || profiles.stream().anyMatch(p->!p.equals("enterprise")) || !"required".equals(environment.getProperty("agent.security.mode")))
   throw new IllegalStateException("ENTERPRISE_FORMAL_PROFILE_REQUIRED");
 }
 /** Metadata-only test seam: no refresh, constructors, database or provider requests. */
 static void registerModules(AnnotationConfigApplicationContext context) {
  var scan=EnterpriseAgentApplication.class.getAnnotation(ComponentScan.class);
  var scanner=new ClassPathBeanDefinitionScanner(context);
  scanner.addExcludeFilter((metadata,factory)->metadata.getClassMetadata().getClassName().equals("com.zxl.zxlaiagent.research.ResearchConfiguration"));
  scanner.scan(scan.basePackages());
 }
 @Bean public ToolCallback[] allTools(){return new ToolCallback[0];}
 /** Formal Research uses ScopedResearchFactory; legacy unscoped fallback is denied, never networked. */
 @Bean public ResearchRunner researchRunner(){
  return new ResearchRunner(new ResearchRunner.Gateway(){
   public List<ResearchRunner.Tool> discover(Duration d,BooleanSupplier c){throw denied();}
   public String call(String n,String i,Duration d,BooleanSupplier c){throw denied();}
  },new ResearchRunner.Model(){
   public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,BooleanSupplier c){throw denied();}
   public String answer(String q,List<JsonNode> e,Duration d,BooleanSupplier c){throw denied();}
  },(hits,d,c)->{throw denied();});
 }
 private static IllegalStateException denied(){return new IllegalStateException("UNSCOPED_RESEARCH_DISABLED");}
}
