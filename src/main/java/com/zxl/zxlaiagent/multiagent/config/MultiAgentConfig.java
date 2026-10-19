package com.zxl.zxlaiagent.multiagent.config;

import com.zxl.zxlaiagent.multiagent.agents.*;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class MultiAgentConfig {

    private final OrchestratorAgent orchestratorAgent;
    private final PlannerAgent plannerAgent;
    private final CodeAgent codeAgent;
    private final ResearchAgent researchAgent;
    private final SupervisorAgent supervisorAgent;
    private final AgentMessageBus messageBus;

    public MultiAgentConfig(ChatModel chatModel, AgentMessageBus messageBus) {
        this.messageBus = messageBus;
        this.orchestratorAgent = new OrchestratorAgent(chatModel);
        this.plannerAgent = new PlannerAgent(chatModel);
        this.codeAgent = new CodeAgent(chatModel);
        this.researchAgent = new ResearchAgent(chatModel);
        this.supervisorAgent = new SupervisorAgent(chatModel);
        injectMessageBus();
    }

    private void injectMessageBus() {
        setField(plannerAgent, "messageBus", messageBus);
        setField(codeAgent, "messageBus", messageBus);
        setField(researchAgent, "messageBus", messageBus);
        setField(supervisorAgent, "messageBus", messageBus);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.set(target, value);
            }
        } catch (Exception e) {
            log.warn("Could not set field {} on {}: {}", fieldName, target.getClass().getSimpleName(), e.getMessage());
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @Bean
    public OrchestratorAgent orchestratorAgent() {
        return orchestratorAgent;
    }

    @PostConstruct
    public void registerAgents() {
        orchestratorAgent.init(messageBus);
        orchestratorAgent.registerAgent(plannerAgent);
        orchestratorAgent.registerAgent(codeAgent);
        orchestratorAgent.registerAgent(researchAgent);
        orchestratorAgent.registerAgent(supervisorAgent);
        log.info("Multi-agent system initialized with {} agents", 5);
        log.info("Agents: orchestrator, planner, code, research, supervisor");
    }
}
