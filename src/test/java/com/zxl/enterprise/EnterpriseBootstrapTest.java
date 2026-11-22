package com.zxl.enterprise;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseBootstrapTest {
 @Test void skillConfigurationActuallyRefreshesWithEnterpriseCompatibilityBeans() {
  try(var context=new AnnotationConfigApplicationContext()) {
   context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",java.util.Map.of("agent.security.mode","required")));
   // Instantiate the real SkillConfiguration, without bringing in its unrelated network/database collaborators.
   context.register(com.zxl.zxlaiagent.skills.SkillConfiguration.class);
   context.addBeanFactoryPostProcessor(factory->factory.getBeanDefinition("scopedSkillRunner").setLazyInit(true));
   var compatibility=new EnterpriseAgentApplication();
   for(var method:EnterpriseAgentApplication.class.getDeclaredMethods()) {
    if(method.isAnnotationPresent(org.springframework.context.annotation.Bean.class) && method.getReturnType().getName().startsWith("com.zxl.zxlaiagent.research.ResearchRunner"))
     context.registerBean(method.getName(),(Class)method.getReturnType(),()->{try{return method.invoke(compatibility);}catch(Exception failure){throw new IllegalStateException(failure);}});
   }
   context.refresh();
   assertNotNull(context.getBean(com.zxl.zxlaiagent.skills.SkillRunner.class));
   assertNotNull(context.getBean(com.zxl.zxlaiagent.skills.SkillRegistry.class));
   assertThrows(IllegalStateException.class,()->context.getBean(com.zxl.zxlaiagent.research.ResearchRunner.Gateway.class).discover(java.time.Duration.ofSeconds(1),()->false));
  }
 }
 @Test void rejectsDowngradeAndMixedProfiles() {
  assertThrows(IllegalStateException.class,()->EnterpriseAgentApplication.validate(new MockEnvironment().withProperty("agent.security.mode","legacy-demo")));
  var env=new MockEnvironment().withProperty("agent.security.mode","required");env.setActiveProfiles("enterprise","local");
  assertThrows(IllegalStateException.class,()->EnterpriseAgentApplication.validate(env));
  env.setActiveProfiles("enterprise");assertDoesNotThrow(()->EnterpriseAgentApplication.validate(env));
 }
 @Test void profileRequiresFormalModeAndDisablesAutomaticMcp()throws Exception {
  var properties=new YamlPropertySourceLoader().load("enterprise",new ClassPathResource("application-enterprise.yml")).get(0);
  assertEquals("required",properties.getProperty("agent.security.mode"));
  assertEquals(false,properties.getProperty("spring.ai.mcp.client.enabled"));
 }
 @Test void scanRetainsFormalModulesAndNeverRegistersLegacyRagOrTools() {
  try(var context=new AnnotationConfigApplicationContext()) {
   context.getEnvironment().setActiveProfiles("enterprise");context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",java.util.Map.of("agent.security.mode","required")));
   EnterpriseAgentApplication.registerModules(context);
   var names=java.util.Arrays.stream(context.getBeanDefinitionNames()).map(n->context.getBeanDefinition(n).getBeanClassName()).filter(java.util.Objects::nonNull).toList();
   assertTrue(names.contains("com.zxl.zxlaiagent.conversation.agent.McpResearchProvider"));
   assertTrue(names.contains("com.zxl.zxlaiagent.ticket.FormalTicketApprovalController"));
   assertTrue(names.contains("com.zxl.zxlaiagent.security.web.FormalKnowledgeGatewayConfiguration"));
   assertFalse(names.stream().anyMatch(n->n.contains("LoveApp")||n.contains("ToolRegistration")||n.contains("ResearchConfiguration")||n.endsWith("ZxlAiAgentApplication")));
  }
 }
}
