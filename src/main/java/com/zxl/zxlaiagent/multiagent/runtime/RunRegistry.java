package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;

/** 单进程登记；终态且实际清理完成的记录才允许过期/淘汰。 */
public final class RunRegistry {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final RuntimeSettings settings;
    private final LongSupplier wallClock;
    public RunRegistry(RuntimeSettings settings) { this(settings, System::currentTimeMillis); }
    public RunRegistry(RuntimeSettings settings, LongSupplier clock) { this.settings = settings; this.wallClock = clock; }
    public synchronized Entry register(WorkflowRun run, ExecutionControl control) {
        return register(run, control, System.nanoTime());
    }
    public synchronized Entry register(WorkflowRun run, ExecutionControl control, long queueStartedNanos) {
        return registerInternal(run, control, queueStartedNanos, OperationBoundary.unrestricted(), false, null);
    }
    public synchronized Entry register(WorkflowRun run, ExecutionControl control, long queueStartedNanos, OperationBoundary boundary) {
        return registerInternal(run,control,queueStartedNanos,boundary,true,null);
    }
    public synchronized Entry register(WorkflowRun run, ExecutionControl control, long queueStartedNanos, OperationBoundary boundary, TrustedRunContext context) {
        Objects.requireNonNull(context,"Trusted run context required");
        if(!context.runId().equals(run.id))throw new IllegalArgumentException("Trusted context must belong to this run");
        return registerInternal(run,control,queueStartedNanos,boundary,true,context);
    }
    private Entry registerInternal(WorkflowRun run, ExecutionControl control, long queueStartedNanos, OperationBoundary boundary, boolean scoped, TrustedRunContext context) {
        Objects.requireNonNull(boundary, "Explicit run boundary required");
        prune();
        if (entries.size() >= settings.getRegistryCapacity()) {
            String evict = entries.values().stream().filter(Entry::reclaimable).map(e -> e.run.id).findFirst().orElse(null);
            if (evict != null) entries.remove(evict);
        }
        if (entries.size() >= settings.getRegistryCapacity()) throw new WorkflowException("AGENT_CAPACITY_EXCEEDED", "runs", "运行登记容量已满，请等待实际调用清理");
        Entry entry = new Entry(run, control, boundary, scoped, context);
        entry.queueStartedNanos = queueStartedNanos;
        entry.waitingFor = "run";
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
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Entry.class);
        public final WorkflowRun run;
        public final ExecutionControl control;
        public final OperationBoundary boundary;
        public final boolean scoped;
        public final TrustedRunContext trustedContext;
        public final CompletableFuture<WorkflowOutcome> result = new CompletableFuture<>();
        private final AtomicInteger tasks = new AtomicInteger(), models = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<Long> terminalNanos = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean cleanupRecorded = new java.util.concurrent.atomic.AtomicBoolean();
        public volatile boolean coordinating;
        public volatile Runnable queuedWork;
        public volatile long queueStartedNanos;
        public volatile String waitingFor;
        private Entry(WorkflowRun run, ExecutionControl control, OperationBoundary boundary, boolean scoped, TrustedRunContext trustedContext) {
            this.run = run;
            this.control = control;
            this.boundary = boundary;
            this.scoped = scoped;
            this.trustedContext = trustedContext;
        }
        public void complete(WorkflowOutcome outcome) {
            if (scoped) terminalNanos.compareAndSet(null, System.nanoTime());
            recordCleanup();
            result.complete(outcome);
        }
        public ExecutionLease taskLease() { return lease(tasks); }
        public ExecutionLease modelLease() { return lease(models); }
        private ExecutionLease lease(AtomicInteger counter) {
            counter.incrementAndGet();
            return new ExecutionLease(() -> {
                counter.decrementAndGet();
                recordCleanup();
            });
        }
        public void coordinationFinished() {
            coordinating = false;
            recordCleanup();
        }
        private void recordCleanup() {
            Long start = terminalNanos.get();
            if (start == null || !reclaimable() || !cleanupRecorded.compareAndSet(false, true)) return;
            long elapsed = System.nanoTime() - start;
            LOG.info("Agent run cleanup runId={} cleanupMs={}", run.id, TimeUnit.NANOSECONDS.toMillis(elapsed));
        }
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
