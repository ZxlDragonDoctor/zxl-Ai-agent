package com.zxl.zxlaiagent.multiagent.bus;

import com.zxl.zxlaiagent.multiagent.model.AgentEvent;
import com.zxl.zxlaiagent.multiagent.model.AgentMessage;
import com.zxl.zxlaiagent.multiagent.model.EventType;
import com.zxl.zxlaiagent.multiagent.model.MessageType;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentMessageBusCapacityTest {

    @Test
    void rejectsWorkWhenBoundedExecutorAndQueueAreFull() throws Exception {
        AgentMessageBus bus = new AgentMessageBus(1, 1, 1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var active = bus.submitTask(() -> {
                started.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return "active";
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            var queued = bus.submitTask(() -> "queued");
            var rejected = bus.submitTask(() -> "must not run");

            CompletionException error = assertThrows(CompletionException.class, rejected::join);
            assertInstanceOf(WorkflowException.class, error.getCause());
            assertEquals("AGENT_CAPACITY_EXCEEDED", ((WorkflowException) error.getCause()).getCode());
            assertEquals(1, bus.capacityMetrics().get("busActive"));
            assertEquals(1, bus.capacityMetrics().get("busQueueSize"));
            assertEquals(1L, bus.capacityMetrics().get("rejectedTasks"));

            release.countDown();
            assertEquals("active", active.get(1, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            bus.stop();
        }
    }

    @Test
    void reportsFullMessageAndBestEffortEventQueues() {
        AgentMessageBus bus = new AgentMessageBus(1, 1, 1, 1);
        try {
            bus.publishEvent(AgentEvent.of("fixture", EventType.TASK_STARTED));
            bus.publishEvent(AgentEvent.of("fixture", EventType.TASK_COMPLETED));
            assertEquals(1L, bus.capacityMetrics().get("rejectedEvents"));

            bus.sendMessage(AgentMessage.create("fixture", "target", MessageType.QUERY, "first"));
            WorkflowException error = assertThrows(WorkflowException.class,
                    () -> bus.sendMessage(AgentMessage.create("fixture", "target", MessageType.QUERY, "overflow")));
            assertEquals("AGENT_CAPACITY_EXCEEDED", error.getCode());
            assertEquals(1L, bus.capacityMetrics().get("rejectedMessages"));
        } finally {
            bus.stop();
        }
    }

    @Test
    void aFailingEventSubscriberDoesNotBlockOtherSubscribers() throws Exception {
        AgentMessageBus bus = new AgentMessageBus(1, 4, 4, 4);
        CountDownLatch healthySubscriberRan = new CountDownLatch(1);
        try {
            bus.addEventListener(EventType.TASK_STARTED, event -> { throw new IllegalStateException("observer failed"); });
            bus.addEventListener(EventType.TASK_STARTED, event -> healthySubscriberRan.countDown());
            bus.start();
            bus.publishEvent(AgentEvent.of("fixture", EventType.TASK_STARTED));
            assertTrue(healthySubscriberRan.await(3, TimeUnit.SECONDS));
            assertEquals(0, bus.capacityMetrics().get("eventQueueSize"));
        } finally {
            bus.stop();
        }
    }
}
