package com.zxl.zxlaiagent.agent;

import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.agent.model.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises execution control with local steps, without a Spring context or model calls. */
class BaseAgentBudgetTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 50})
    void acceptsValidBudgetWhileIdle(int budget) {
        ScriptedAgent agent = new ScriptedAgent(step -> {});

        assertDoesNotThrow(() -> agent.setMaxSteps(budget));

        assertEquals(budget, agent.getMaxSteps());
        assertEquals(AgentState.IDLE, agent.getState());
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 51, Integer.MAX_VALUE})
    void rejectsInvalidBudgetWithoutChangingStateOrBudget(int budget) {
        ScriptedAgent agent = new ScriptedAgent(step -> {});
        int originalBudget = agent.getMaxSteps();

        assertThrows(IllegalArgumentException.class, () -> agent.setMaxSteps(budget));

        assertEquals(originalBudget, agent.getMaxSteps());
        assertEquals(AgentState.IDLE, agent.getState());
        assertDoesNotThrow(() -> agent.setMaxSteps(20));
    }

    @ParameterizedTest
    @EnumSource(value = AgentState.class, names = {"RUNNING", "FINISHED", "ERROR"})
    void rejectsBudgetChangesOutsideIdleWithoutChangingState(AgentState state) {
        ScriptedAgent agent = new ScriptedAgent(step -> {});
        agent.setState(state);
        int originalBudget = agent.getMaxSteps();

        assertThrows(IllegalStateException.class, () -> agent.setMaxSteps(20));

        assertEquals(originalBudget, agent.getMaxSteps());
        assertEquals(state, agent.getState());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void synchronousCompletionOnLastAllowedStepIsNotALimit(int budget) throws Exception {
        ScriptedAgent agent = completingAgent(budget);
        agent.setMaxSteps(budget);

        String result = agent.run("本地步骤演示");

        assertEquals(budget, agent.getCurrentStep());
        assertEquals(AgentState.FINISHED, agent.getState());
        assertFalse(result.contains("Reached max steps"));
    }

    @Test
    void synchronousRunStopsAtBudgetWhenMoreWorkRemains() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(step -> {});
        agent.setMaxSteps(3);

        String result = agent.run("本地步骤演示");

        assertEquals(3, agent.getCurrentStep());
        assertEquals(AgentState.FINISHED, agent.getState());
        assertTrue(result.contains("Reached max steps (3)"));
    }

    @Test
    void synchronousRunStopsImmediatelyOnErrorState() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(step -> {});
        agent.onStep = step -> agent.setState(AgentState.ERROR);
        agent.setMaxSteps(3);

        String result = agent.run("本地步骤演示");

        assertEquals(1, agent.getCurrentStep());
        assertEquals(AgentState.ERROR, agent.getState());
        assertTrue(result.startsWith("执行错误"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void streamingCompletionOnLastAllowedStepIsNotALimit(int budget) throws Exception {
        ScriptedAgent agent = completingAgent(budget);
        agent.setMaxSteps(budget);

        agent.runStream("本地步骤演示");
        agent.awaitCompletion();

        assertEquals(budget, agent.getCurrentStep());
        assertEquals(AgentState.FINISHED, agent.getState());
        assertEquals("RUN_COMPLETED", agent.terminalEvent().type());
        assertEquals("completed", agent.terminalEvent().status());
    }

    @Test
    void streamingRunStopsAtBudgetWhenMoreWorkRemains() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(step -> {});
        agent.setMaxSteps(3);

        agent.runStream("本地步骤演示");
        agent.awaitCompletion();

        assertEquals(3, agent.getCurrentStep());
        assertEquals(AgentState.FINISHED, agent.getState());
        assertEquals("limit", agent.terminalEvent().status());
        assertEquals(List.of(1, 2, 3), agent.events.stream()
                .filter(event -> "STEP_STARTED".equals(event.type()))
                .map(AgentExecutionEvent::step).toList());
    }

    @Test
    void streamingRunDoesNotTurnAnErrorStateIntoSuccess() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(step -> {});
        agent.onStep = step -> agent.setState(AgentState.ERROR);
        agent.setMaxSteps(3);

        agent.runStream("本地步骤演示");
        agent.awaitCompletion();

        assertEquals(1, agent.getCurrentStep());
        assertEquals(AgentState.ERROR, agent.getState());
        assertEquals("RUN_FAILED", agent.terminalEvent().type());
        assertEquals("error", agent.terminalEvent().status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void budgetRemainsFixedDuringAnActualRun(boolean streaming) throws Exception {
        CountDownLatch stepStarted = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        ScriptedAgent agent = completingAgent(1);
        IntConsumer originalStep = agent.onStep;
        agent.onStep = step -> {
            stepStarted.countDown();
            try {
                if (!releaseStep.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("步骤未按预期释放");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("步骤被中断", e);
            }
            originalStep.accept(step);
        };
        agent.setMaxSteps(3);

        if (streaming) agent.runStream("本地步骤演示");
        else CompletableFuture.runAsync(() -> agent.run("本地步骤演示"));

        try {
            assertTrue(stepStarted.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> agent.setMaxSteps(50));
            assertEquals(3, agent.getMaxSteps());
            assertEquals(AgentState.RUNNING, agent.getState());
        } finally {
            releaseStep.countDown();
        }
        agent.awaitCompletion();
        assertEquals(AgentState.FINISHED, agent.getState());
    }

    private ScriptedAgent completingAgent(int finishAt) {
        ScriptedAgent agent = new ScriptedAgent(step -> {});
        agent.onStep = step -> {
            if (step == finishAt) agent.setState(AgentState.FINISHED);
        };
        return agent;
    }

    private static final class ScriptedAgent extends BaseAgent {
        private IntConsumer onStep;
        private final List<AgentExecutionEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);

        private ScriptedAgent(IntConsumer onStep) {
            this.onStep = onStep;
        }

        @Override
        public String step() {
            onStep.accept(getCurrentStep());
            return "步骤 " + getCurrentStep();
        }

        @Override
        protected void emitExecutionEvent(AgentExecutionEvent event) {
            events.add(event);
            super.emitExecutionEvent(event);
        }

        @Override
        protected void cleanup() {
            completed.countDown();
        }

        private void awaitCompletion() throws InterruptedException {
            assertTrue(completed.await(5, TimeUnit.SECONDS), "Agent 未在预期时间内结束");
        }

        private AgentExecutionEvent terminalEvent() {
            List<AgentExecutionEvent> terminalEvents = events.stream()
                    .filter(event -> "RUN_COMPLETED".equals(event.type()) || "RUN_FAILED".equals(event.type()))
                    .toList();
            assertEquals(1, terminalEvents.size(), "每次运行应有且只有一个终态事件");
            return terminalEvents.get(0);
        }
    }
}
