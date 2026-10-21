package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.model.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** 协调线程只等待，工作线程负责阻塞 I/O；两者不会互相占满同一个池。 */
public final class ExecutionRuntime implements AutoCloseable {
    private final RuntimeSettings settings;
    private final ThreadPoolExecutor coordinators, workers;
    private final Semaphore modelPermits;
    private final Map<String, RunRegistry.Entry> bindings = new ConcurrentHashMap<>();
    private final Set<String> leasedAgents = new HashSet<>();
    private final Map<String, Deque<Object>> roleWaiters = new HashMap<>();
    private final AtomicInteger models = new AtomicInteger(), peakModels = new AtomicInteger(), rejected = new AtomicInteger(), peakQueue = new AtomicInteger();
    public ExecutionRuntime(RuntimeSettings settings) {
        settings.validate(); this.settings = settings;
        coordinators = pool(settings.getConcurrentRuns(), settings.getRunQueueCapacity(), "agent-run-");
        workers = pool(settings.getWorkerThreads(), settings.getWorkerQueueCapacity(), "agent-io-");
        modelPermits = new Semaphore(settings.getModelConcurrency(), true);
    }
    private static ThreadPoolExecutor pool(int size, int capacity, String prefix) {
        AtomicInteger ids = new AtomicInteger();
        return new ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity, true), r -> {
            Thread t = new Thread(r, prefix + ids.incrementAndGet()); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public void dispatch(Runnable run) {
        try { coordinators.execute(run); peakQueue.accumulateAndGet(coordinators.getQueue().size(), Math::max); }
        catch (RejectedExecutionException e) { rejected.incrementAndGet(); throw capacity("runs.queue"); }
    }
    public boolean removeQueued(Runnable work) { return coordinators.remove(work); }
    public <T> CompletableFuture<T> async(Task task, Supplier<T> action) {
        try { return CompletableFuture.supplyAsync(() -> { check(task); return action.get(); }, workers); }
        catch (RejectedExecutionException e) { throw capacity("workers.queue"); }
    }
    public void bind(Task task, RunRegistry.Entry entry) { bindings.put(task.getId(), entry); }
    public void unbind(Task task) { bindings.remove(task.getId()); }
    public ExecutionControl control(Task task) { var entry = bindings.get(task.getId()); return entry == null ? null : entry.control; }
    public void check(Task task) { ExecutionControl control = control(task); if (control != null) control.check(); }
    public <T> T modelCall(Task task, Supplier<T> call) {
        RunRegistry.Entry entry = bindings.get(task.getId());
        ExecutionControl control = entry == null ? null : entry.control;
        boolean acquired = false;
        try {
            long queued = System.nanoTime();
            while (!acquired) {
                check(task);
                if (System.nanoTime() - queued >= TimeUnit.MILLISECONDS.toNanos(settings.getQueueTimeoutMs()))
                    throw new WorkflowException("CAPACITY_QUEUE_TIMEOUT", "models", "等待模型调用容量超时");
                acquired = modelPermits.tryAcquire(25, TimeUnit.MILLISECONDS);
            }
            Supplier<ExecutionLease> begin = () -> {
                if (!TaskLifecycle.recordModelCall(task)) throw new WorkflowException("TASK_ALREADY_TERMINAL", "tasks." + task.getId(), "任务已结束，不能调用模型");
                peakModels.accumulateAndGet(models.incrementAndGet(), Math::max);
                return entry == null ? new ExecutionLease(() -> {}) : entry.modelLease();
            };
            try (ExecutionLease ignored = control == null ? begin.get() : control.admit(begin)) {
                try { return call.get(); } finally { models.decrementAndGet(); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new WorkflowException("EXECUTION_INTERRUPTED", "models", "模型容量等待被中断", e);
        } finally { if (acquired) modelPermits.release(); }
    }
    public synchronized AgentLease tryAgent(String type, Collection<AgentInterface> agents) {
        if (!roleWaiters.getOrDefault(type, new ArrayDeque<>()).isEmpty()) return null;
        return take(type, agents);
    }
    public AgentLease awaitAgent(String type, Supplier<Collection<AgentInterface>> agents, ExecutionControl control, Runnable queued) {
        Object ticket = new Object(); long start = System.nanoTime(); boolean reported = false;
        synchronized (this) { roleWaiters.computeIfAbsent(type, key -> new ArrayDeque<>()).addLast(ticket); }
        try {
            while (true) {
                control.check();
                synchronized (this) {
                    if (roleWaiters.get(type).peekFirst() == ticket) {
                        AgentLease lease = control.admit(() -> take(type, agents.get()));
                        if (lease != null) return lease;
                    }
                    if (System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(settings.getQueueTimeoutMs()))
                        throw new WorkflowException("CAPACITY_QUEUE_TIMEOUT", "agents." + type, "等待工作角色容量超时：" + type);
                }
                if (!reported) { queued.run(); reported = true; }
                synchronized (this) { wait(25); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new WorkflowException("EXECUTION_INTERRUPTED", "agents." + type, "角色排队被中断", e);
        } finally {
            synchronized (this) { roleWaiters.get(type).remove(ticket); notifyAll(); }
        }
    }
    private AgentLease take(String type, Collection<AgentInterface> agents) {
        AgentInterface agent = agents.stream().filter(a -> type.equals(a.getAgentType()) && a.getState() == AgentState.IDLE && !leasedAgents.contains(a.getAgentId()))
                .min(Comparator.comparing(AgentInterface::getAgentId)).orElse(null);
        if (agent == null) return null;
        leasedAgents.add(agent.getAgentId());
        return new AgentLease(agent, new ExecutionLease(() -> { synchronized (this) { leasedAgents.remove(agent.getAgentId()); notifyAll(); } }));
    }
    public record AgentLease(AgentInterface agent, ExecutionLease ownership) implements AutoCloseable { @Override public void close() { ownership.close(); } }
    private static WorkflowException capacity(String path) { return new WorkflowException("AGENT_CAPACITY_EXCEEDED", path, "执行容量已满，请稍后重试"); }
    public Map<String, Object> metrics() {
        return Map.of("coordinating", coordinators.getActiveCount(), "queuedRuns", coordinators.getQueue().size(),
                "queuePeak", peakQueue.get(), "rejected", rejected.get(), "actualModelCalls", models.get(), "modelPeak", peakModels.get(),
                "runThreads", coordinators.getLargestPoolSize(), "workerThreads", workers.getLargestPoolSize(), "maxModels", settings.getModelConcurrency());
    }
    @Override public void close() { coordinators.shutdown(); workers.shutdown(); }
    public boolean awaitTermination(long duration, TimeUnit unit) throws InterruptedException { return coordinators.awaitTermination(duration, unit) && workers.awaitTermination(duration, unit); }
}
