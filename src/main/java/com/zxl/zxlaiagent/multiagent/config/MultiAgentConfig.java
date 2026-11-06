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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.zxl.zxlaiagent.multiagent.runtime.RuntimeSettings;
import com.alibaba.cloud.ai.autoconfigure.dashscope.*;

@Configuration
@Slf4j
@EnableConfigurationProperties(RuntimeSettings.class)
public class MultiAgentConfig {

    private final OrchestratorAgent orchestratorAgent;
    private final PlannerAgent plannerAgent;
    private final CodeAgent codeAgent;
    private final ResearchAgent researchAgent;
    private final SupervisorAgent supervisorAgent;
    private final AgentMessageBus messageBus;

    public MultiAgentConfig(AgentMessageBus messageBus, ObjectMapper mapper, RuntimeSettings limits,
                            DashScopeConnectionProperties connection, DashScopeChatProperties chat,
                            PlanParser parser, PlanValidator validator, TaskInputBuilder inputs, TaskPromptRenderer renderer,
                            com.zxl.zxlaiagent.research.ResearchRunner researchRunner,
                            com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer scopedStreamer,
                            org.springframework.beans.factory.ObjectProvider<com.zxl.zxlaiagent.research.ScopedResearchFactory> scopedResearch,
                            org.springframework.core.env.Environment environment) {
        this.messageBus = messageBus;
        ChatModel chatModel = MultiAgentModelFactory.create(connection, chat, limits);
        this.orchestratorAgent = new OrchestratorAgent(chatModel, mapper, validator, inputs, limits, System::nanoTime);
        this.plannerAgent = new PlannerAgent(chatModel, parser, mapper);
        this.codeAgent = new CodeAgent(chatModel, renderer);
        this.researchAgent = new ResearchAgent(chatModel, renderer,researchRunner);
        var researchFactory=scopedResearch.getIfAvailable();
        if(researchFactory!=null)researchAgent.attachScopedResearchFactory(researchFactory);
        this.supervisorAgent = new SupervisorAgent(chatModel, renderer);
        // Orchestrator owns scheduling; model calls belong to these worker agents.
        for(AbstractBaseAgent agent:java.util.List.of(plannerAgent,codeAgent,researchAgent,supervisorAgent)) {
            agent.attachScopedStreamer(scopedStreamer);
            if("required".equals(environment.getProperty("agent.security.mode")))agent.requireScopedExecution();
        }
        plannerAgent.attachMessageBus(messageBus);
        codeAgent.attachMessageBus(messageBus);
        researchAgent.attachMessageBus(messageBus);
        supervisorAgent.attachMessageBus(messageBus);
    }

    @Bean(destroyMethod = "shutdown")
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
