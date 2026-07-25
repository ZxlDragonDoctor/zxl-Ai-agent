package com.zxl.zxlaiagent.multiagent.harness;

import com.zxl.zxlaiagent.multiagent.model.AgentInterface;
import com.zxl.zxlaiagent.multiagent.model.Task;
import com.zxl.zxlaiagent.multiagent.model.TaskStatus;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
public class AgentHarness {

    private final AgentInterface agent;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("harness-" + r.hashCode());
        return t;
    });
    private final List<EvalResult> evalHistory = new CopyOnWriteArrayList<>();

    public AgentHarness(AgentInterface agent) {
        this.agent = agent;
    }

    @Value
    @Builder
    public static class EvalResult {
        String taskId;
        String description;
        TaskStatus status;
        long durationMs;
        String result;
        String error;
        boolean success;
    }

    @Value
    @Builder
    public static class HarnessReport {
        String agentId;
        String agentType;
        int totalTasks;
        int passed;
        int failed;
        double successRate;
        double avgResponseTimeMs;
        double p50ResponseTimeMs;
        double p95ResponseTimeMs;
        double p99ResponseTimeMs;
        long totalDurationMs;
        List<EvalResult> results;
    }

    public EvalResult runSingleTask(String description, Map<String, Object> input, long timeoutMs) {
        long start = System.nanoTime();
        Task task = Task.create(description);
        if (input != null) task.setInput(input);

        try {
            String result = agent.executeTask(task).get(timeoutMs, TimeUnit.MILLISECONDS);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            EvalResult evalResult = EvalResult.builder()
                    .taskId(task.getId())
                    .description(description)
                    .status(TaskStatus.COMPLETED)
                    .durationMs(duration)
                    .result(result)
                    .success(true)
                    .build();
            evalHistory.add(evalResult);
            return evalResult;
        } catch (TimeoutException e) {
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            EvalResult evalResult = EvalResult.builder()
                    .taskId(task.getId())
                    .description(description)
                    .status(TaskStatus.FAILED)
                    .durationMs(duration)
                    .error("Timeout after " + timeoutMs + "ms")
                    .success(false)
                    .build();
            evalHistory.add(evalResult);
            return evalResult;
        } catch (Exception e) {
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            EvalResult evalResult = EvalResult.builder()
                    .taskId(task.getId())
                    .description(description)
                    .status(TaskStatus.FAILED)
                    .durationMs(duration)
                    .error(e.getMessage())
                    .success(false)
                    .build();
            evalHistory.add(evalResult);
            return evalResult;
        }
    }

    public List<EvalResult> runBenchmark(List<BenchmarkTask> benchmark, long timeoutMs) {
        List<EvalResult> results = new ArrayList<>();
        for (BenchmarkTask bt : benchmark) {
            log.info("Running benchmark task: {} (complexity: {})", bt.description(), bt.complexity());
            EvalResult result = runSingleTask(bt.description(), bt.input(), timeoutMs);
            results.add(result);
            log.info("Benchmark task {}: {} ({}ms)", bt.description(),
                    result.isSuccess() ? "PASSED" : "FAILED", result.getDurationMs());
        }
        return results;
    }

    public HarnessReport generateReport() {
        if (evalHistory.isEmpty()) {
            return HarnessReport.builder()
                    .agentId(agent.getAgentId())
                    .agentType(agent.getAgentType())
                    .totalTasks(0)
                    .passed(0)
                    .failed(0)
                    .successRate(0)
                    .avgResponseTimeMs(0)
                    .results(List.of())
                    .build();
        }

        List<Long> durations = evalHistory.stream()
                .map(EvalResult::getDurationMs)
                .sorted()
                .toList();
        long totalDuration = evalHistory.stream().mapToLong(EvalResult::getDurationMs).sum();
        long passed = evalHistory.stream().filter(EvalResult::isSuccess).count();
        long failed = evalHistory.size() - passed;
        double avgTime = durations.isEmpty() ? 0 : durations.stream().mapToLong(Long::longValue).average().orElse(0);

        return HarnessReport.builder()
                .agentId(agent.getAgentId())
                .agentType(agent.getAgentType())
                .totalTasks(evalHistory.size())
                .passed((int) passed)
                .failed((int) failed)
                .successRate(evalHistory.isEmpty() ? 0 : (double) passed / evalHistory.size() * 100)
                .avgResponseTimeMs(avgTime)
                .p50ResponseTimeMs(percentile(durations, 50))
                .p95ResponseTimeMs(percentile(durations, 95))
                .p99ResponseTimeMs(percentile(durations, 99))
                .totalDurationMs(totalDuration)
                .results(new ArrayList<>(evalHistory))
                .build();
    }

    public record BenchmarkTask(String description, Map<String, Object> input, int complexity) {
        public BenchmarkTask(String description) {
            this(description, Map.of(), 1);
        }
        public BenchmarkTask(String description, int complexity) {
            this(description, Map.of(), complexity);
        }
    }

    private long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public void printReport(HarnessReport report) {
        System.out.println("\n===========================================");
        System.out.println("  AGENT HARNESS EVALUATION REPORT");
        System.out.println("===========================================");
        System.out.println("  Agent:       " + report.getAgentId() + " (" + report.getAgentType() + ")");
        System.out.println("  Total Tasks: " + report.getTotalTasks());
        System.out.println("  Passed:      " + report.getPassed());
        System.out.println("  Failed:      " + report.getFailed());
        System.out.println("  Success Rate: " + String.format("%.1f%%", report.getSuccessRate()));
        System.out.println("  Avg Time:    " + String.format("%.0fms", report.getAvgResponseTimeMs()));
        System.out.println("  P50 Time:    " + report.getP50ResponseTimeMs() + "ms");
        System.out.println("  P95 Time:    " + report.getP95ResponseTimeMs() + "ms");
        System.out.println("  P99 Time:    " + report.getP99ResponseTimeMs() + "ms");
        System.out.println("  Total Time:  " + report.getTotalDurationMs() + "ms");
        System.out.println("-------------------------------------------");
        for (EvalResult r : report.getResults()) {
            String status = r.isSuccess() ? "PASS" : "FAIL";
            System.out.printf("  [%s] %s (%dms)%n", status, r.getDescription(), r.getDurationMs());
            if (!r.isSuccess() && r.getError() != null) {
                System.out.println("       Error: " + r.getError());
            }
        }
        System.out.println("===========================================\n");
    }
}
