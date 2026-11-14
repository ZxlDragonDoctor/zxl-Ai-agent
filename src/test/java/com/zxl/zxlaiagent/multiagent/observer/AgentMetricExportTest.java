package com.zxl.zxlaiagent.multiagent.observer;

import io.micrometer.core.instrument.Metrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AgentMetricExportTest {
    @Test
    void managementClientReadsSnapshotAndClosingUnregistersIt() throws Exception {
        var name = new javax.management.ObjectName("com.zxl.agent:type=ModelMetrics");
        var server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var registration = new AgentMetricExportConfiguration().agentMetricExport(mapper)) {
            Metrics.timer("agent.model.admitted", "outcome", "timeout").record(Duration.ofMillis(5));
            var snapshot = mapper.readTree((String) server.getAttribute(name, "Snapshot"));
            assertEquals(1, snapshot.size());
            assertEquals("timeout", snapshot.get(0).path("outcome").asText());
            assertEquals(5.0, snapshot.get(0).path("totalMs").asDouble());
        }
        assertFalse(server.isRegistered(name));
    }

    @Test
    void exportsOnlyAllowlistedTimersAndOutcomes() {
        try (var export = new AgentMetricExport()) {
            Metrics.timer("agent.model.queue", "outcome", "completed").record(Duration.ofMillis(20));
            Metrics.timer("agent.model.queue", "outcome", "completed").record(Duration.ofMillis(30));
            Metrics.timer("agent.model.queue", "outcome", "private-identifier").record(Duration.ofMillis(1));
            Metrics.timer("agent.model.admitted", "outcome", "completed", "runId", "private-id")
                    .record(Duration.ofMillis(1));
            assertEquals(java.util.List.of(new AgentMetricExport.TimerSnapshot(
                    "agent.model.queue", "completed", 2, 50.0, 30.0)), export.snapshot());
        }
    }
}
