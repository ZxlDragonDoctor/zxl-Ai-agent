package com.zxl.zxlaiagent.multiagent.bus;

import com.zxl.zxlaiagent.multiagent.model.AgentEvent;
import com.zxl.zxlaiagent.multiagent.model.AgentInterface;
import com.zxl.zxlaiagent.multiagent.model.AgentMessage;
import com.zxl.zxlaiagent.multiagent.model.EventType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
@Slf4j
public class AgentMessageBus {

    private final Map<String, AgentInterface> agentRegistry = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<AgentEvent>>> eventListeners = new ConcurrentHashMap<>();
    private final BlockingQueue<AgentMessage> messageQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<AgentEvent> eventQueue = new LinkedBlockingQueue<>();
    private volatile boolean running = false;
    private Thread messageProcessor;
    private Thread eventProcessor;
    private final ExecutorService taskExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("agent-bus-" + r.hashCode());
        return t;
    });

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
        messageQueue.offer(message);
        publishEvent(AgentEvent.of(message.getSourceAgentId(), EventType.MESSAGE_SENT));
    }

    public void publishEvent(AgentEvent event) {
        eventQueue.offer(event);
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, taskExecutor);
    }

    private void dispatchMessage(AgentMessage message) {
        if ("*".equals(message.getTargetAgentId())) {
            agentRegistry.values().forEach(agent -> {
                if (!agent.getAgentId().equals(message.getSourceAgentId())) {
                    taskExecutor.execute(() -> {
                        try {
                            agent.handleMessage(message);
                        } catch (Exception e) {
                            log.error("Error delivering message to agent {}: {}", agent.getAgentId(), e.getMessage());
                        }
                    });
                }
            });
        } else {
            AgentInterface target = agentRegistry.get(message.getTargetAgentId());
            if (target != null) {
                taskExecutor.execute(() -> {
                    try {
                        target.handleMessage(message);
                    } catch (Exception e) {
                        log.error("Error delivering message to agent {}: {}", target.getAgentId(), e.getMessage());
                    }
                });
            } else {
                log.warn("Target agent not found: {}", message.getTargetAgentId());
            }
        }
    }

    private void dispatchEvent(AgentEvent event) {
        var listeners = eventListeners.get(event.getType().name());
        if (listeners != null) {
            listeners.forEach(listener -> {
                taskExecutor.execute(() -> {
                    try {
                        listener.accept(event);
                    } catch (Exception e) {
                        log.error("Error in event listener", e);
                    }
                });
            });
        }
        log.debug("Event: {} from agent {}", event.getType(), event.getAgentId());
    }
}
