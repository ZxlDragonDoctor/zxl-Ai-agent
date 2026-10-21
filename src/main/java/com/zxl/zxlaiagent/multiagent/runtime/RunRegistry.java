package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** 单进程登记；终态且实际清理完成的记录才允许过期/淘汰。 */
public final class RunRegistry {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final RuntimeSettings settings;
    private final LongSupplier wallClock;
    public RunRegistry(RuntimeSettings settings) { this(settings, System::currentTimeMillis); }
    public RunRegistry(RuntimeSettings settings, LongSupplier clock) { this.settings = settings; this.wallClock = clock; }
    public synchronized Entry register(WorkflowRun run, ExecutionControl control) {
        prune();
        if (entries.size() >= settings.getRegistryCapacity()) {
            String evict = entries.values().stream().filter(Entry::reclaimable).map(e -> e.run.id).findFirst().orElse(null);
            if (evict != null) entries.remove(evict);
        }
        if (entries.size() >= settings.getRegistryCapacity()) throw new WorkflowException("AGENT_CAPACITY_EXCEEDED", "runs", "运行登记容量已满，请等待实际调用清理");
        Entry entry = new Entry(run, control);
        entries.put(run.id, entry);
        run.setRuntimeView(entry::view);
        return entry;
    }
    public synchronized Entry find(String id) { prune(); return entries.get(id); }
    public synchronized List<Entry> entries() { prune(); return List.copyOf(entries.values()); }
    public View view(String id) {
        Entry e = find(id);
        if (e == null) return null;
        WorkflowRun.Report report = e.run.latestReport();
        return new View(id, report == null ? null : report.withRuntime(e.view()), e.view());
    }
    private void prune() {
        long now = wallClock.getAsLong();
        entries.values().removeIf(e -> e.reclaimable() && now - e.run.outcome().finishedAt() >= settings.getRetentionMs());
    }
    public record View(String executionId, WorkflowRun.Report report, RuntimeView runtime) {}
    public static final class Entry {
        public final WorkflowRun run;
        public final ExecutionControl control;
        public final CompletableFuture<WorkflowOutcome> result = new CompletableFuture<>();
        private final AtomicInteger tasks = new AtomicInteger(), models = new AtomicInteger();
        public volatile boolean coordinating;
        public volatile Runnable queuedWork;
        public volatile long queueStartedNanos;
        public volatile String waitingFor;
        private Entry(WorkflowRun run, ExecutionControl control) { this.run = run; this.control = control; }
        public ExecutionLease taskLease() { tasks.incrementAndGet(); return new ExecutionLease(tasks::decrementAndGet); }
        public ExecutionLease modelLease() { models.incrementAndGet(); return new ExecutionLease(models::decrementAndGet); }
        public int activeTasks() { return tasks.get(); }
        public RuntimeView view() {
            boolean terminal = run.outcome() != null;
            var reason = control.stopReason();
            return new RuntimeView(control.deadlineAt(), reason != null && reason.code().equals("RUN_CANCELLED"),
                    tasks.get(), models.get(), terminal && (tasks.get() > 0 || models.get() > 0 || coordinating), waitingFor);
        }
        private boolean reclaimable() { return run.outcome() != null && tasks.get() == 0 && models.get() == 0 && !coordinating; }
    }
}
