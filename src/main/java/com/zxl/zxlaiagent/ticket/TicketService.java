package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.multiagent.runtime.ExecutionControl;
import com.zxl.zxlaiagent.multiagent.runtime.retry.RetryExecutor;

public final class TicketService {
    public record Receipt(Ticket ticket, int attempts) {}
    private final TicketGateway gateway;
    private final RetryExecutor retries;
    public TicketService(TicketGateway gateway, RetryExecutor retries) { this.gateway = gateway; this.retries = retries; }
    public Receipt create(TicketCommand command, ExecutionControl control) {
        var result = retries.execute(() -> gateway.create(command, control), control);
        return new Receipt(result.value(), result.attempts());
    }
}
