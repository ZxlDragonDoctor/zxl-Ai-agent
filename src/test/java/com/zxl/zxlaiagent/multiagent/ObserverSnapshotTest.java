package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.observer.AgentObserver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ObserverSnapshotTest {
    @Test void observerStoresCommittedSnapshotsAndMeasuresDurationNotEpochTime() {
        AgentMessageBus bus = mock(AgentMessageBus.class);
        Map<EventType, List<Consumer<AgentEvent>>> listeners = new EnumMap<>(EventType.class);
        doAnswer(invocation -> {
            EventType type = invocation.getArgument(0);
            Consumer<AgentEvent> listener = invocation.getArgument(1);
            listeners.computeIfAbsent(type, key -> new ArrayList<>()).add(listener);
            return null;
        }).when(bus).addEventListener(any(), any());
        AgentInterface agent = mock(AgentInterface.class);
        when(agent.getAgentId()).thenReturn("worker"); when(agent.getAgentType()).thenReturn("research");
        when(bus.getAgent("worker")).thenReturn(agent);
        AgentObserver observer = new AgentObserver();
        ReflectionTestUtils.setField(observer, "messageBus", bus); observer.init();
        Consumer<AgentEvent> emit = event -> listeners.get(event.getType()).forEach(listener -> listener.accept(event));
        emit.accept(AgentEvent.of("worker", EventType.AGENT_REGISTERED));
        emit.accept(AgentEvent.of("worker", EventType.TASK_STARTED));
        observer.getSystemSnapshot();
        assertEquals(0, observer.getAgentMetrics("worker").getCompletedTasks());
        TaskSnapshot committed = new TaskSnapshot(TaskStatus.COMPLETED, "actual", null, "worker", 1, 100, 145);
        emit.accept(AgentEvent.of("worker", EventType.TASK_COMPLETED, Map.of("taskId", "task", "execution", committed)));
        observer.getSystemSnapshot();
        assertEquals(45, observer.getAgentMetrics("worker").getAvgResponseTimeMs());
        assertEquals(1, observer.getAgentMetrics("worker").getCompletedTasks());
        var history = observer.getRecentEvents(10);
        assertEquals(3, history.size());
        assertSame(committed, history.get(2).getData().get("execution"));
        emit.accept(AgentEvent.of("worker", EventType.TASK_BLOCKED));
        assertEquals(3, history.size());
        assertEquals(4, observer.getRecentEvents(10).size());
    }
}
