package com.zxl.zxlaiagent.multiagent.config;

import com.zxl.zxlaiagent.multiagent.agents.*;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.planning.*;
import com.zxl.zxlaiagent.multiagent.execution.*;
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

    public MultiAgentConfig(ChatModel chatModel, AgentMessageBus messageBus, ObjectMapper mapper,
                            PlanParser parser, PlanValidator validator, TaskInputBuilder inputs, TaskPromptRenderer renderer) {
        this.messageBus = messageBus;
        this.orchestratorAgent = new OrchestratorAgent(chatModel, mapper, validator, inputs);
        this.plannerAgent = new PlannerAgent(chatModel, parser, mapper);
        this.codeAgent = new CodeAgent(chatModel, renderer);
        this.researchAgent = new ResearchAgent(chatModel, renderer);
        this.supervisorAgent = new SupervisorAgent(chatModel, renderer);
        plannerAgent.attachMessageBus(messageBus);
        codeAgent.attachMessageBus(messageBus);
        researchAgent.attachMessageBus(messageBus);
        supervisorAgent.attachMessageBus(messageBus);
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
