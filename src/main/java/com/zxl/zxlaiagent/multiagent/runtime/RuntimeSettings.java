package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix = "agent.runtime")
public class RuntimeSettings {
    private long defaultBudgetMs = 90000, maxBudgetMs = 180000;
    private long planningTimeoutMs = 60000, taskTimeoutMs = 120000, synthesisTimeoutMs = 60000;
    private long queueTimeoutMs = 30000, retentionMs = 600000;
    private int concurrentRuns = 2, runQueueCapacity = 8, registryCapacity = 200;
    private int workerThreads = 4, workerQueueCapacity = 16, modelConcurrency = 3;
    private int connectTimeoutMs = 5000, requestTimeoutMs = 30000;
    public long budget(Long requested) {
        long value = requested == null ? defaultBudgetMs : requested;
        if (value <= 0 || value > maxBudgetMs)
            throw new WorkflowException("INVALID_EXECUTION_BUDGET", "timeoutMs", "执行预算必须为 1 至 " + maxBudgetMs + " 毫秒");
        return value;
    }
    public void validate() {
        if (defaultBudgetMs <= 0 || maxBudgetMs < defaultBudgetMs || concurrentRuns < 1 || runQueueCapacity < 1
                || registryCapacity < concurrentRuns + runQueueCapacity || workerThreads < 1 || workerQueueCapacity < 1
                || modelConcurrency < 1 || queueTimeoutMs < 1 || retentionMs < 1 || planningTimeoutMs < 1
                || taskTimeoutMs < 1 || synthesisTimeoutMs < 1 || connectTimeoutMs < 1 || requestTimeoutMs < 1)
            throw new IllegalArgumentException("Invalid agent.runtime limits");
    }
}
