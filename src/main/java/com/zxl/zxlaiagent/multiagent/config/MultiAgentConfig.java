package com.zxl.zxlaiagent.multiagent.config;

import com.zxl.zxlaiagent.multiagent.agents.CodeAgent;
import com.zxl.zxlaiagent.multiagent.agents.PlannerAgent;
import com.zxl.zxlaiagent.multiagent.agents.ResearchAgent;
import com.zxl.zxlaiagent.multiagent.agents.SupervisorAgent;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class MultiAgentConfig {

    private final OrchestratorAgent orchestratorAgent;
    private final PlannerAgent plannerAgent;
    private final CodeAgent codeAgent;
    private final ResearchAgent researchAgent;
    private final SupervisorAgent supervisorAgent;

    public MultiAgentConfig(ChatModel chatModel, AgentMessageBus messageBus) {
        this.orchestratorAgent = new OrchestratorAgent(chatModel);
        this.plannerAgent = new PlannerAgent(chatModel);
        this.codeAgent = new CodeAgent(chatModel);
        this.researchAgent = new ResearchAgent(chatModel);
        this.supervisorAgent = new SupervisorAgent(chatModel);
        injectMessageBus();
    }

    private void injectMessageBus() {
        var fieldSetter = new java.util.function.Consumer<Object>() {
            @Override
            public void accept(Object agent) {
                try {
                    var field = agent.getClass().getSuperclass().getDeclaredField("messageBus");
                    field.setAccessible(true);
                    var busField = orchestratorAgent.getClass().getDeclaredField("messageBus");
                    busField.setAccessible(true);
                    field.set(agent, busField.get(orchestratorAgent));
                } catch (Exception e) {
                    log.warn("Could not inject message bus: {}", e.getMessage());
                }
            }
        };
        fieldSetter.accept(plannerAgent);
        fieldSetter.accept(codeAgent);
        fieldSetter.accept(researchAgent);
        fieldSetter.accept(supervisorAgent);
    }

    @PostConstruct
    public void registerAgents() {
        orchestratorAgent.registerAgent(plannerAgent);
        orchestratorAgent.registerAgent(codeAgent);
        orchestratorAgent.registerAgent(researchAgent);
        orchestratorAgent.registerAgent(supervisorAgent);
        log.info("Multi-agent system initialized with {} agents", 5);
        log.info("Agents: orchestrator, planner, code, research, supervisor");
    }

    public OrchestratorAgent getOrchestrator() {
        return orchestratorAgent;
    }
}
