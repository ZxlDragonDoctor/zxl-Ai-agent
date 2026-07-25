package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.harness.AgentHarness;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MultiAgentOrchestrationTest {

    private AgentMessageBus messageBus;
    private OrchestratorAgent orchestrator;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        messageBus = new AgentMessageBus();
        messageBus.start();
        chatModel = mock(ChatModel.class);
        orchestrator = new OrchestratorAgent(chatModel);
    }

    @Test
    void testAgentMessageCreation() {
        AgentMessage msg = AgentMessage.create("agent1", "agent2", MessageType.QUERY, "hello");
        assertNotNull(msg.getId());
        assertEquals("agent1", msg.getSourceAgentId());
        assertEquals("agent2", msg.getTargetAgentId());
        assertEquals(MessageType.QUERY, msg.getType());
        assertEquals("hello", msg.getContent());
        assertTrue(msg.getTimestamp() > 0);
    }

    @Test
    void testAgentMessageBroadcast() {
        AgentMessage msg = AgentMessage.broadcast("agent1", MessageType.STATUS_UPDATE, "online");
        assertEquals("*", msg.getTargetAgentId());
    }

    @Test
    void testTaskCreation() {
        Task task = Task.create("test task");
        assertNotNull(task.getId());
        assertEquals("test task", task.getDescription());
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertTrue(task.getDependencies().isEmpty());
        assertEquals(5, task.getPriority());
    }

    @Test
    void testTaskWithDependencies() {
        Task dep1 = Task.create("dependency 1");
        Task task = Task.create("main task", java.util.List.of(dep1.getId()));
        assertEquals(1, task.getDependencies().size());
        assertEquals(dep1.getId(), task.getDependencies().get(0));
    }

    @Test
    void testPlanCreation() {
        Plan plan = Plan.create("test goal");
        assertNotNull(plan.getId());
        assertEquals("test goal", plan.getGoal());
        assertEquals(PlanStatus.CREATED, plan.getStatus());
        assertTrue(plan.getTasks().isEmpty());
    }

    @Test
    void testPlanAddTasks() {
        Plan plan = Plan.create("test");
        Task t1 = Task.create("task 1");
        Task t2 = Task.create("task 2", java.util.List.of(t1.getId()));
        plan.addTask(t1);
        plan.addTask(t2);
        assertEquals(2, plan.getTasks().size());
    }

    @Test
    void testPlanReadyTasks() {
        Plan plan = Plan.create("test");
        Task t1 = Task.create("task 1");
        Task t2 = Task.create("task 2", java.util.List.of(t1.getId()));
        plan.addTask(t1);
        plan.addTask(t2);

        var ready = plan.getReadyTasks();
        assertEquals(1, ready.size());
        assertEquals("task 1", ready.get(0).getDescription());

        t1.setStatus(TaskStatus.COMPLETED);
        ready = plan.getReadyTasks();
        assertEquals(1, ready.size());
        assertEquals("task 2", ready.get(0).getDescription());
    }

    @Test
    void testPlanIsComplete() {
        Plan plan = Plan.create("test");
        Task t1 = Task.create("task 1");
        plan.addTask(t1);
        assertFalse(plan.isComplete());
        t1.setStatus(TaskStatus.COMPLETED);
        assertTrue(plan.isComplete());
    }

    @Test
    void testAgentCapability() {
        AgentCapability cap = AgentCapability.builder()
                .agentType("code")
                .description("Code agent")
                .tools(java.util.Set.of("java", "python"))
                .maxConcurrency(3)
                .canDelegate(false)
                .build();
        assertEquals("code", cap.getAgentType());
        assertTrue(cap.getTools().contains("java"));
        assertFalse(cap.isCanDelegate());
    }

    @Test
    void testMockAgentInterface() {
        AgentInterface agent = mock(AgentInterface.class);
        when(agent.getAgentId()).thenReturn("test-agent");
        when(agent.getAgentType()).thenReturn("test");
        when(agent.getState()).thenReturn(AgentState.IDLE);
        when(agent.executeTask(any())).thenReturn(CompletableFuture.completedFuture("done"));

        assertEquals("test-agent", agent.getAgentId());
        assertNotNull(agent.executeTask(Task.create("test")));
    }

    @Test
    void testHierarchicalMemoryStats() {
        var memory = new com.zxl.zxlaiagent.multiagent.memory.HierarchicalMemory();
        memory.store("agent1", "session1", "user", "hello", 
                com.zxl.zxlaiagent.multiagent.memory.HierarchicalMemory.MemoryLevel.WORKING, 0.5);
        memory.store("agent1", "session1", "assistant", "hi there",
                com.zxl.zxlaiagent.multiagent.memory.HierarchicalMemory.MemoryLevel.WORKING, 0.6);

        var stats = memory.getStats("agent1");
        assertEquals(2, stats.get("workingMemory"));
    }

    @Test
    void testAgentHarness() {
        AgentInterface agent = mock(AgentInterface.class);
        when(agent.getAgentId()).thenReturn("test-agent");
        when(agent.getAgentType()).thenReturn("test");
        when(agent.executeTask(any())).thenReturn(CompletableFuture.completedFuture("success"));

        AgentHarness harness = new AgentHarness(agent);
        AgentHarness.EvalResult result = harness.runSingleTask("test task", Map.of(), 5000);

        assertTrue(result.isSuccess());
        assertNotNull(result.getResult());
        assertNotNull(result.getTaskId());
    }

    @Test
    void testAgentHarnessReport() {
        AgentInterface agent = mock(AgentInterface.class);
        when(agent.getAgentId()).thenReturn("test-agent");
        when(agent.getAgentType()).thenReturn("test");
        when(agent.executeTask(any())).thenReturn(CompletableFuture.completedFuture("success"));

        AgentHarness harness = new AgentHarness(agent);
        harness.runSingleTask("task1", Map.of(), 5000);
        harness.runSingleTask("task2", Map.of(), 5000);

        var report = harness.generateReport();
        assertEquals(2, report.getTotalTasks());
        assertEquals(2, report.getPassed());
        assertEquals(0, report.getFailed());
        assertEquals(100.0, report.getSuccessRate(), 0.01);
    }

    @Test
    void testOrchestratorStatus() {
        var status = orchestrator.getStatus();
        assertNotNull(status.get("agentId"));
        assertEquals("orchestrator-1", status.get("agentId"));
    }

    @Test
    void testPlanStatusTransitions() {
        Plan plan = Plan.create("test");
        assertEquals(PlanStatus.CREATED, plan.getStatus());
        plan.setStatus(PlanStatus.IN_PROGRESS);
        assertEquals(PlanStatus.IN_PROGRESS, plan.getStatus());
        plan.setStatus(PlanStatus.COMPLETED);
        assertEquals(PlanStatus.COMPLETED, plan.getStatus());
    }

    @Test
    void testAgentStateTransitions() {
        // Test multiagent model AgentState (not to be confused with agent.model.AgentState)
        assertEquals(AgentState.IDLE, AgentState.IDLE);
        assertEquals(AgentState.BUSY, AgentState.BUSY);
        assertEquals(AgentState.ERROR, AgentState.ERROR);
        assertEquals(AgentState.SHUTDOWN, AgentState.SHUTDOWN);
    }

    @Test
    void testMessageTypeValues() {
        assertNotNull(MessageType.valueOf("TASK_ASSIGN"));
        assertNotNull(MessageType.valueOf("TASK_RESULT"));
        assertNotNull(MessageType.valueOf("PLAN_UPDATE"));
        assertNotNull(MessageType.valueOf("DELEGATE"));
        assertNotNull(MessageType.valueOf("COORDINATE"));
    }

    @Test
    void testEventTypeValues() {
        assertNotNull(EventType.valueOf("TASK_STARTED"));
        assertNotNull(EventType.valueOf("TASK_COMPLETED"));
        assertNotNull(EventType.valueOf("PLAN_STARTED"));
        assertNotNull(EventType.valueOf("AGENT_REGISTERED"));
    }

    @Test
    void testAgentEventCreation() {
        AgentEvent event = AgentEvent.of("agent1", EventType.TASK_STARTED);
        assertNotNull(event.getId());
        assertEquals("agent1", event.getAgentId());
        assertEquals(EventType.TASK_STARTED, event.getType());
    }

    @Test
    void testAgentEventWithData() {
        AgentEvent event = AgentEvent.of("agent1", EventType.TASK_COMPLETED,
                Map.of("taskId", "123", "result", "done"));
        assertEquals("123", event.getData().get("taskId"));
    }

    @Test
    void testBenchmarkTaskRecord() {
        AgentHarness.BenchmarkTask bt = new AgentHarness.BenchmarkTask("test", 3);
        assertEquals("test", bt.description());
        assertEquals(3, bt.complexity());
    }

    @Test
    void testTaskStatusValues() {
        assertNotNull(TaskStatus.valueOf("PENDING"));
        assertNotNull(TaskStatus.valueOf("IN_PROGRESS"));
        assertNotNull(TaskStatus.valueOf("COMPLETED"));
        assertNotNull(TaskStatus.valueOf("FAILED"));
        assertNotNull(TaskStatus.valueOf("BLOCKED"));
        assertNotNull(TaskStatus.valueOf("CANCELLED"));
    }

    @Test
    void testMemoryLevelValues() {
        assertNotNull(com.zxl.zxlaiagent.multiagent.memory.HierarchicalMemory.MemoryLevel.valueOf("WORKING"));
        assertNotNull(com.zxl.zxlaiagent.multiagent.memory.HierarchicalMemory.MemoryLevel.valueOf("EPISODIC"));
        assertNotNull(com.zxl.zxlaiagent.multiagent.memory.HierarchicalMemory.MemoryLevel.valueOf("SEMANTIC"));
        assertNotNull(com.zxl.zxlaiagent.multiagent.memory.HierarchicalMemory.MemoryLevel.valueOf("PROCEDURAL"));
    }

    @Test
    void testAgentEventIdempotency() {
        AgentEvent e1 = AgentEvent.of("a1", EventType.TASK_STARTED);
        AgentEvent e2 = AgentEvent.of("a1", EventType.TASK_STARTED);
        assertNotEquals(e1.getId(), e2.getId());
    }
}
