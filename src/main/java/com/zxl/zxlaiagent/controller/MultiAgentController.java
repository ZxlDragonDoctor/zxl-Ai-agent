package com.zxl.zxlaiagent.controller;

import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.harness.AgentHarness;
import com.zxl.zxlaiagent.multiagent.model.AgentInterface;
import com.zxl.zxlaiagent.multiagent.model.WorkflowError;
import com.zxl.zxlaiagent.multiagent.execution.WorkflowOutcome;
import com.zxl.zxlaiagent.multiagent.model.AgentMessage;
import com.zxl.zxlaiagent.multiagent.model.MessageType;
import com.zxl.zxlaiagent.multiagent.observer.AgentObserver;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/ai/multiagent")
@Slf4j
public class MultiAgentController {

    @Resource
    private OrchestratorAgent orchestratorAgent;

    @Resource
    private AgentMessageBus messageBus;

    @Resource
    private AgentObserver agentObserver;

    public record OrchestrationRequest(String goal, Map<String, Object> context, Long timeoutMs) {
        public OrchestrationRequest(String goal, Map<String, Object> context) { this(goal, context, null); }
    }

    @PostMapping("/orchestrate")
    public Map<String, Object> orchestrate(@RequestBody OrchestrationRequest request) {
        try {
            WorkflowOutcome outcome = request.timeoutMs() == null ? orchestratorAgent.run(request.goal(), request.context(), report -> {})
                    : orchestratorAgent.run(request.goal(), request.context(), request.timeoutMs(), report -> {});
            if (outcome.successful()) return Map.of("success", true, "result", outcome.result(), "outcome", outcome);
            WorkflowError error = outcome.error();
            return Map.of("success", false, "code", error.code(), "path", error.path(), "error", error.message(), "outcome", outcome);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            WorkflowError error = e instanceof java.util.concurrent.TimeoutException
                    ? new WorkflowError("REQUEST_TIMEOUT", "request", "请求等待超时，后台执行可能仍在进行") : WorkflowError.from(e);
            return Map.of("success", false, "code", error.code(), "path", error.path(), "error", error.message(), "executionStatus", "unknown");
        }
    }

    /** 本地单用户演示；生产接入前须绑定认证用户与租户，不能把 UUID 当授权。 */
    @PostMapping("/runs")
    public ResponseEntity<?> submitRun(@RequestBody OrchestrationRequest request) {
        try {
            var handle = orchestratorAgent.submit(request.goal(), request.context(), request.timeoutMs(), report -> {});
            return ResponseEntity.accepted().body(Map.of("executionId", handle.executionId()));
        } catch (Exception e) { return ResponseEntity.badRequest().body(WorkflowError.from(e)); }
    }

    @GetMapping("/runs/{executionId}")
    public ResponseEntity<?> getRun(@PathVariable String executionId) {
        var view = orchestratorAgent.runStatus(executionId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    @PostMapping("/runs/{executionId}/cancel")
    public ResponseEntity<?> cancelRun(@PathVariable String executionId) {
        var receipt = orchestratorAgent.cancel(executionId);
        return ResponseEntity.status(receipt.status().equals("not_found") ? 404 : 200).body(receipt);
    }

    @GetMapping(value = "/orchestrate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter orchestrateStream(@RequestParam String goal) {
        return stream(new OrchestrationRequest(goal, Map.of()));
    }

    @PostMapping(value = "/orchestrate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter orchestrateStream(@RequestBody OrchestrationRequest request) {
        return stream(request);
    }

    private SseEmitter stream(OrchestrationRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);
        var connected = new java.util.concurrent.atomic.AtomicBoolean(true);
        emitter.onCompletion(() -> connected.set(false));
        emitter.onTimeout(() -> connected.set(false));
        emitter.onError(error -> connected.set(false));
        java.util.function.BiConsumer<String, Object> send = (name, data) -> {
            if (!connected.get()) return;
            try { emitter.send(SseEmitter.event().name(name).data(data)); }
            catch (Exception e) { connected.set(false); }
        };
        try {
            var handle = orchestratorAgent.submit(request.goal(), request.context(), request.timeoutMs(), report -> send.accept("report", report));
            handle.result().whenComplete((outcome, failure) -> {
                if (failure != null) send.accept("error", WorkflowError.from(failure));
                else {
                if (outcome.successful()) send.accept("complete", outcome.result());
                else send.accept("error", outcome.error());
                }
                try { emitter.complete(); } catch (Exception ignored) { connected.set(false); }
            });
        } catch (Exception e) { send.accept("error", WorkflowError.from(e)); emitter.complete(); }
        return emitter;
    }

    @GetMapping("/status")
    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = orchestratorAgent.getStatus();
        status.put("observer", agentObserver.getSystemSnapshot());
        return status;
    }

    @GetMapping("/agents")
    public Map<String, Object> listAgents() {
        var agents = messageBus.getAllAgents();
        return Map.of("agents", agents.entrySet().stream()
                .map(e -> Map.of(
                        "id", e.getKey(),
                        "type", e.getValue().getAgentType(),
                        "state", e.getValue().getState(),
                        "capability", e.getValue().getCapability()
                ))
                .toList());
    }

    @PostMapping("/agents/{agentId}/message")
    public Map<String, String> sendMessageToAgent(
            @PathVariable String agentId,
            @RequestBody Map<String, String> body) {
        AgentMessage message = AgentMessage.create("user", agentId,
                MessageType.QUERY, body.getOrDefault("content", ""));
        messageBus.sendMessage(message);
        return Map.of("status", "sent", "messageId", message.getId());
    }

    @GetMapping("/events")
    public Map<String, Object> getRecentEvents(@RequestParam(defaultValue = "50") int limit) {
        var events = agentObserver.getRecentEvents(limit);
        return Map.of("events", events, "total", events.size());
    }

    @GetMapping("/harness/{agentId}")
    public Map<String, Object> runHarness(@PathVariable String agentId,
                                          @RequestParam(defaultValue = "simple test") String task) {
        AgentInterface agent = messageBus.getAgent(agentId);
        if (agent == null) {
            return Map.of("error", "Agent not found: " + agentId);
        }
        AgentHarness harness = new AgentHarness(agent);
        AgentHarness.EvalResult result = harness.runSingleTask(task, Map.of(), 60000);
        var report = harness.generateReport();
        harness.printReport(report);
        return Map.of(
                "success", result.isSuccess(),
                "durationMs", result.getDurationMs(),
                "result", result.getResult() != null ? result.getResult() : result.getError(),
                "report", report
        );
    }

    @GetMapping("/harness/benchmark")
    public Map<String, Object> runBenchmark() {
        var results = new java.util.ArrayList<Map<String, Object>>();
        for (var entry : messageBus.getAllAgents().entrySet()) {
            AgentHarness harness = new AgentHarness(entry.getValue());
            var tasks = java.util.List.of(
                    new AgentHarness.BenchmarkTask("Hello, what can you do?", 1),
                    new AgentHarness.BenchmarkTask("Analyze the weather impact on agriculture", 2),
                    new AgentHarness.BenchmarkTask("Write a complete Java Spring Boot REST controller", 3)
            );
            harness.runBenchmark(tasks, 60000);
            var report = harness.generateReport();
            harness.printReport(report);
            results.add(Map.of(
                    "agentId", entry.getKey(),
                    "agentType", entry.getValue().getAgentType(),
                    "report", report
            ));
        }
        return Map.of("benchmarkResults", results);
    }
}
