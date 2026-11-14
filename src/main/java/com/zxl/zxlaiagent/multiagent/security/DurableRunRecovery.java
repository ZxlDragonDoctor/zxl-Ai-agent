package com.zxl.zxlaiagent.multiagent.security;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
final class DurableRunRecovery {
    private final ScopedMultiAgentService runs;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "durable-run-recovery");
        thread.setDaemon(true);
        return thread;
    });

    DurableRunRecovery(ScopedMultiAgentService runs) {
        this.runs = runs;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::recover, 0, 5, TimeUnit.SECONDS);
    }

    private void recover() {
        try {
            runs.recoverExpiredRuns();
        } catch (RuntimeException failure) {
            log.warn("Saved run recovery failed ({})", failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }
}
