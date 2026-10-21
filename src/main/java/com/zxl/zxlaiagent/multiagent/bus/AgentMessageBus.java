package com.zxl.zxlaiagent.multiagent.bus;

import com.zxl.zxlaiagent.multiagent.model.AgentEvent;
import com.zxl.zxlaiagent.multiagent.model.AgentInterface;
import com.zxl.zxlaiagent.multiagent.model.AgentMessage;
import com.zxl.zxlaiagent.multiagent.model.EventType;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import com.zxl.zxlaiagent.multiagent.runtime.RuntimeSettings;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

@Component
@Slf4j
public class AgentMessageBus {

    private final Map<String, AgentInterface> agentRegistry = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<AgentEvent>>> eventListeners = new ConcurrentHashMap<>();
    private final BlockingQueue<AgentMessage> messageQueue;
    private final BlockingQueue<AgentEvent> eventQueue;
    private volatile boolean running = false;
    private Thread messageProcessor;
    private Thread eventProcessor;
    private final ThreadPoolExecutor taskExecutor;
    private final LongAdder rejectedTasks = new LongAdder();
    private final LongAdder rejectedMessages = new LongAdder();
    private final LongAdder rejectedEvents = new LongAdder();

    public AgentMessageBus() {
        this(8, 256, 256, 512);
    }

    @Autowired
    public AgentMessageBus(RuntimeSettings settings) {
        this(settings.getBusThreads(), settings.getBusQueueCapacity(), settings.getMessageQueueCapacity(), settings.getEventQueueCapacity());
    }

    AgentMessageBus(int threads, int taskQueueCapacity, int messageQueueCapacity, int eventQueueCapacity) {
        if (threads < 1 || taskQueueCapacity < 1 || messageQueueCapacity < 1 || eventQueueCapacity < 1) {
            throw new IllegalArgumentException("Agent message bus capacities must be positive");
        }
        AtomicInteger ids = new AtomicInteger();
        taskExecutor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(taskQueueCapacity), r -> {
                    Thread thread = new Thread(r, "agent-bus-" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        messageQueue = new ArrayBlockingQueue<>(messageQueueCapacity);
        eventQueue = new ArrayBlockingQueue<>(eventQueueCapacity);
    }

    @PostConstruct
    public void start() {
        running = true;
        messageProcessor = new Thread(() -> {
            while (running) {
                try {
                    AgentMessage message = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        dispatchMessage(message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error processing message", e);
                }
            }
        }, "msg-bus-processor");
        messageProcessor.setDaemon(true);
        messageProcessor.start();

        eventProcessor = new Thread(() -> {
            while (running) {
                try {
                    AgentEvent event = eventQueue.poll(1, TimeUnit.SECONDS);
                    if (event != null) {
                        dispatchEvent(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error processing event", e);
                }
            }
        }, "event-bus-processor");
        eventProcessor.setDaemon(true);
        eventProcessor.start();

        log.info("AgentMessageBus started");
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (messageProcessor != null) messageProcessor.interrupt();
        if (eventProcessor != null) eventProcessor.interrupt();
        taskExecutor.shutdown();
        log.info("AgentMessageBus stopped");
    }

    public void registerAgent(AgentInterface agent) {
        agentRegistry.put(agent.getAgentId(), agent);
        publishEvent(AgentEvent.of(agent.getAgentId(), EventType.AGENT_REGISTERED));
        log.info("Agent registered: {} ({})", agent.getAgentId(), agent.getAgentType());
    }

    public void unregisterAgent(String agentId) {
        agentRegistry.remove(agentId);
        publishEvent(AgentEvent.of(agentId, EventType.AGENT_UNREGISTERED));
        log.info("Agent unregistered: {}", agentId);
    }

    public void sendMessage(AgentMessage message) {
        if (!messageQueue.offer(message)) {
            rejectedMessages.increment();
            throw new WorkflowException("AGENT_CAPACITY_EXCEEDED", "messageBus.messages", "Agent 消息队列已满，请稍后重试");
        }
        publishEvent(AgentEvent.of(message.getSourceAgentId(), EventType.MESSAGE_SENT));
    }

    public void publishEvent(AgentEvent event) {
        if (!eventQueue.offer(event)) {
            rejectedEvents.increment();
            log.warn("Agent event queue is full; dropping best-effort event {} from {}", event.getType(), event.getAgentId());
        }
    }

    public void addEventListener(EventType type, Consumer<AgentEvent> listener) {
        eventListeners.computeIfAbsent(type.name(), k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public AgentInterface getAgent(String agentId) {
        return agentRegistry.get(agentId);
    }

    public Map<String, AgentInterface> getAllAgents() {
        return Map.copyOf(agentRegistry);
    }

    public <T> CompletableFuture<T> submitTask(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            taskExecutor.execute(() -> {
            try {
                    result.complete(task.call());
            } catch (Exception e) {
                    result.completeExceptionally(e);
            }
            });
        } catch (RejectedExecutionException rejected) {
            rejectedTasks.increment();
            result.completeExceptionally(new WorkflowException("AGENT_CAPACITY_EXCEEDED", "messageBus.tasks", "Agent 消息总线执行容量已满，请稍后重试", rejected));
        }
        return result;
    }

    public Map<String, Object> capacityMetrics() {
        Map<String, Object> metrics = new java.util.HashMap<>();
        metrics.put("busThreads", taskExecutor.getPoolSize());
        metrics.put("busActive", taskExecutor.getActiveCount());
        metrics.put("busQueueSize", taskExecutor.getQueue().size());
        metrics.put("busQueueCapacity", taskExecutor.getQueue().remainingCapacity() + taskExecutor.getQueue().size());
        metrics.put("messageQueueSize", messageQueue.size());
        metrics.put("messageQueueCapacity", messageQueue.size() + messageQueue.remainingCapacity());
        metrics.put("eventQueueSize", eventQueue.size());
        metrics.put("eventQueueCapacity", eventQueue.size() + eventQueue.remainingCapacity());
        metrics.put("rejectedTasks", rejectedTasks.sum());
        metrics.put("rejectedMessages", rejectedMessages.sum());
        metrics.put("rejectedEvents", rejectedEvents.sum());
        return Map.copyOf(metrics);
    }

    private void dispatchMessage(AgentMessage message) {
        if ("*".equals(message.getTargetAgentId())) {
            agentRegistry.values().forEach(agent -> {
                if (!agent.getAgentId().equals(message.getSourceAgentId())) {
                    dispatchBounded(() -> {
                        try {
                            agent.handleMessage(message);
                        } catch (Exception e) {
                            log.error("Error delivering message to agent {}: {}", agent.getAgentId(), e.getMessage());
                        }
                    }, "message");
                }
            });
        } else {
            AgentInterface target = agentRegistry.get(message.getTargetAgentId());
            if (target != null) {
                dispatchBounded(() -> {
                    try {
                        target.handleMessage(message);
                    } catch (Exception e) {
                        log.error("Error delivering message to agent {}: {}", target.getAgentId(), e.getMessage());
                    }
                }, "message");
            } else {
                log.warn("Target agent not found: {}", message.getTargetAgentId());
            }
        }
    }

    private void dispatchEvent(AgentEvent event) {
        var listeners = eventListeners.get(event.getType().name());
        if (listeners != null) {
            listeners.forEach(listener -> {
                dispatchBounded(() -> {
                    try {
                        listener.accept(event);
                    } catch (Exception e) {
                        log.error("Error in event listener", e);
                    }
                }, "event");
            });
        }
        log.debug("Event: {} from agent {}", event.getType(), event.getAgentId());
    }

    /** Queue saturation applies caller-runs backpressure to the single queue consumer instead of losing a dispatched item. */
    private void dispatchBounded(Runnable task, String kind) {
        try {
            taskExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            rejectedTasks.increment();
            log.warn("Agent bus executor is full while dispatching {}; applying caller-runs backpressure", kind);
            if (running) task.run();
        }
    }
}
