package com.zxl.zxlaiagent.multiagent.observer;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class AgentMetricExport implements AutoCloseable {
    private static final Set<String> NAMES = Set.of("agent.model.queue", "agent.model.admitted");
    private static final Set<String> OUTCOMES =
            Set.of("completed", "failed", "timeout", "cancelled", "interrupted", "denied");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    AgentMetricExport() {
        registry.config().meterFilter(MeterFilter.deny(id ->
                !NAMES.contains(id.getName()) || id.getTags().size() != 1
                        || id.getTag("outcome") == null || !OUTCOMES.contains(id.getTag("outcome"))));
        Metrics.addRegistry(registry);
    }

    List<TimerSnapshot> snapshot() {
        return registry.getMeters().stream()
                .filter(io.micrometer.core.instrument.Timer.class::isInstance)
                .map(io.micrometer.core.instrument.Timer.class::cast)
                .map(timer -> {
                    var value = timer.takeSnapshot();
                    return new TimerSnapshot(timer.getId().getName(), timer.getId().getTag("outcome"),
                            value.count(), value.total(TimeUnit.MILLISECONDS), value.max(TimeUnit.MILLISECONDS));
                })
                .filter(snapshot -> snapshot.count() > 0)
                .sorted(Comparator.comparing(TimerSnapshot::name).thenComparing(TimerSnapshot::outcome))
                .toList();
    }

    @Override public void close() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    record TimerSnapshot(String name, String outcome, long count, double totalMs, double recentMaxMs) {}
}
