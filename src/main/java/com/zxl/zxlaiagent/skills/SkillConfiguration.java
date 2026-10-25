package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.research.ResearchRunner;
import org.springframework.context.annotation.*;
import java.util.Set;

@Configuration
public class SkillConfiguration {
    @Bean public SkillRegistry skillRegistry() {
        var registry = new SkillRegistry(SkillCatalogue.builtIns());
        registry.setDefault("it-ticket-analysis", System.getenv().getOrDefault("LESSON16_DEFAULT_VERSION", "v1"));
        return registry;
    }
    @Bean public SkillExecutionPolicy skillExecutionPolicy() { return new SkillExecutionPolicy(Set.of("knowledge__knowledge_lookup_v1")); }
    @Bean public SkillRunner skillRunner(SkillRegistry registry, SkillExecutionPolicy policy,
            ResearchRunner.Gateway gateway, ResearchRunner.Model model, ResearchRunner.Verifier verifier) {
        return new SkillRunner(registry, policy, gateway, model, verifier);
    }
}
