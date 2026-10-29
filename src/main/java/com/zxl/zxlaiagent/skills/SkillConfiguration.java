package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.research.ResearchRunner;
import org.springframework.context.annotation.*;
import java.util.Set;

@Configuration
public class SkillConfiguration {
    @Bean public SkillRegistry skillRegistry(org.springframework.core.env.Environment environment) {
        boolean formal="required".equals(environment.getProperty("agent.security.mode"));
        var registry = new SkillRegistry(formal?SkillCatalogue.formalBuiltIns():SkillCatalogue.builtIns());
        registry.setDefault("it-ticket-analysis", formal?environment.getProperty("agent.skills.formal-default-version","v3"):System.getenv().getOrDefault("LESSON16_DEFAULT_VERSION", "v1"));
        return registry;
    }
    @Bean public SkillExecutionPolicy skillExecutionPolicy() { return new SkillExecutionPolicy(Set.of("knowledge__knowledge_lookup_v1")); }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="agent.security.mode",havingValue="required")
    public ScopedSkillRunner scopedSkillRunner(SkillRegistry registry,
            com.zxl.zxlaiagent.research.ScopedResearchFactory research,
            com.zxl.zxlaiagent.security.AuthorizationService authorization){
        return new ScopedSkillRunner(registry,research,authorization);
    }
    @Bean public SkillRunner skillRunner(SkillRegistry registry, SkillExecutionPolicy policy,
            ResearchRunner.Gateway gateway, ResearchRunner.Model model, ResearchRunner.Verifier verifier) {
        return new SkillRunner(registry, policy, gateway, model, verifier);
    }
}
