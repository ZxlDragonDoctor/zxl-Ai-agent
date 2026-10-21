package com.zxl.zxlaiagent.controller;

import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import com.zxl.zxlaiagent.multiagent.runtime.ExecutionControl;
import com.zxl.zxlaiagent.multiagent.runtime.retry.*;
import com.zxl.zxlaiagent.ticket.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.List;

/** 显式开启 lesson8-demo profile 后可用的本地单用户实验，不注册为 Agent 写入工具。 */
@RestController
@Profile("lesson8-demo")
@RequestMapping("/ai/learning/tickets")
public class LearningTicketController {
    public record CreateRequest(String operationId, String title, String description, Long timeoutMs) {}
    public record ApiError(String code, String message, boolean retryable) {}
    private final InMemoryTicketGateway gateway = new InMemoryTicketGateway(1000);
    private final TicketService service = new TicketService(gateway, new RetryExecutor(new RetryPolicy(3, 100, 1000)));

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        try {
            long timeout = request.timeoutMs() == null ? 5000 : request.timeoutMs();
            if (timeout < 1 || timeout > 30_000) throw RetryFailure.permanentFailure("INVALID_BUDGET", "工单实验总预算必须在 1–30000 毫秒之间");
            var command = new TicketCommand(request.operationId(), request.title(), request.description());
            var control = new ExecutionControl(System.currentTimeMillis(), Duration.ofMillis(timeout), System::nanoTime);
            return ResponseEntity.ok(service.create(command, control));
        } catch (RetryFailure known) {
            int status = switch (known.code()) {
                case "OPERATION_CONFLICT", "OPERATION_UNCERTAIN" -> 409;
                case "OPERATION_CAPACITY_EXCEEDED", "RETRY_EXHAUSTED", "RETRY_BUDGET_EXHAUSTED" -> 503;
                default -> 400;
            };
            return ResponseEntity.status(status).body(new ApiError(known.code(), known.getMessage(), known.retryable()));
        } catch (WorkflowException stopped) {
            return ResponseEntity.status(408).body(new ApiError(stopped.getCode(), stopped.getMessage(), false));
        }
    }
    @GetMapping public List<Ticket> tickets() { return gateway.tickets(); }
}
