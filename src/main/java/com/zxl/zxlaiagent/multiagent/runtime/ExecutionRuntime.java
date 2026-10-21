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
    private final LongSupplier clock;
    private final ThreadPoolExecutor coordinators, workers;
    private final Semaphore modelPermits;
    private final Map<String, RunRegistry.Entry> bindings = new ConcurrentHashMap<>();
    private final Map<String, QueuedWork<?>> queuedWork = new ConcurrentHashMap<>();
    private final Set<String> leasedAgents = new HashSet<>();
    private final Map<String, Deque<Object>> roleWaiters = new HashMap<>();
    private final AtomicInteger models = new AtomicInteger(), peakModels = new AtomicInteger(), rejected = new AtomicInteger(), peakQueue = new AtomicInteger();
    public ExecutionRuntime(RuntimeSettings settings) {
        this(settings, System::nanoTime);
    }
    public ExecutionRuntime(RuntimeSettings settings, LongSupplier clock) {
        this.clock = clock;
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
        QueuedWork<T> work = new QueuedWork<>(task, action);
        queuedWork.put(task.getId(), work);
        try { workers.execute(work); return work.result; }
        catch (RejectedExecutionException e) { queuedWork.remove(task.getId(), work); throw capacity("workers.queue"); }
    }
    private final class QueuedWork<T> implements Runnable {
        final Task task; final Supplier<T> action; final long queuedAt = clock.getAsLong();
        final CompletableFuture<T> result = new CompletableFuture<>();
        QueuedWork(Task task, Supplier<T> action) { this.task = task; this.action = action; }
        @Override public void run() {
            queuedWork.remove(task.getId(), this);
            try { check(task); checkQueue(queuedAt, "workers.queue"); result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        }
    }
    public void removeStoppedWork(RunRegistry.Entry entry) {
        for (QueuedWork<?> work : queuedWork.values()) if (bindings.get(work.task.getId()) == entry && workers.remove(work)) {
            queuedWork.remove(work.task.getId(), work);
            work.result.completeExceptionally(new WorkflowException(entry.control.stopReason()));
        }
    }
    public void sweepWorkerQueue() {
        for (QueuedWork<?> work : queuedWork.values()) {
            if (clock.getAsLong() - work.queuedAt < TimeUnit.MILLISECONDS.toNanos(settings.getQueueTimeoutMs())) continue;
            if (workers.remove(work)) {
                queuedWork.remove(work.task.getId(), work);
                work.result.completeExceptionally(new WorkflowException("CAPACITY_QUEUE_TIMEOUT", "workers.queue", "工作线程排队时间已达上限"));
            }
        }
    }
    private void checkQueue(long start, String path) {
        if (clock.getAsLong() - start >= TimeUnit.MILLISECONDS.toNanos(settings.getQueueTimeoutMs()))
            throw new WorkflowException("CAPACITY_QUEUE_TIMEOUT", path, "等待执行容量超时");
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
            long queued = clock.getAsLong();
            while (!acquired) {
                check(task);
                checkQueue(queued, "models");
                acquired = modelPermits.tryAcquire(25, TimeUnit.MILLISECONDS);
            }
            checkQueue(queued, "models");
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
        Object ticket = new Object(); long start = clock.getAsLong(); boolean reported = false;
        synchronized (this) { roleWaiters.computeIfAbsent(type, key -> new ArrayDeque<>()).addLast(ticket); }
        try {
            while (true) {
                control.check();
                synchronized (this) {
                    checkQueue(start, "agents." + type);
                    if (roleWaiters.get(type).peekFirst() == ticket) {
                        AgentLease lease = take(type, agents.get());
                        if (lease != null) return lease;
                    }
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
    public synchronized Map<String, Object> metrics() {
        return Map.ofEntries(Map.entry("coordinating", coordinators.getActiveCount()), Map.entry("queuedRuns", coordinators.getQueue().size()),
                Map.entry("queuePeak", peakQueue.get()), Map.entry("rejected", rejected.get()), Map.entry("actualModelCalls", models.get()), Map.entry("modelPeak", peakModels.get()),
                Map.entry("runThreads", coordinators.getLargestPoolSize()), Map.entry("workerThreads", workers.getLargestPoolSize()), Map.entry("maxModels", settings.getModelConcurrency()),
                Map.entry("queuedWorkers", workers.getQueue().size()), Map.entry("leasedAgents", leasedAgents.size()));
    }
    @Override public void close() { coordinators.shutdown(); workers.shutdown(); }
    public boolean awaitTermination(long duration, TimeUnit unit) throws InterruptedException { return coordinators.awaitTermination(duration, unit) && workers.awaitTermination(duration, unit); }
}
