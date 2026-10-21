package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.multiagent.runtime.ExecutionControl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 单进程单用户本地工单实验，不连接企业工单系统。 */
public final class InMemoryTicketGateway implements TicketGateway {
    private final OperationLedger<Ticket> ledger;
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    public InMemoryTicketGateway(int capacity) { ledger = new OperationLedger<>(capacity); }
    @Override public synchronized Ticket create(TicketCommand command, ExecutionControl control) {
        control.check();
        return ledger.execute(command.operationId(), command.fingerprint(), () -> {
            control.check();
            var ticket = new Ticket("T-" + UUID.randomUUID(), command.operationId(), command.title(), command.description());
            tickets.put(ticket.id(), ticket);
            return ticket;
        });
    }
    public synchronized int ticketCount() { return tickets.size(); }
    public synchronized List<Ticket> tickets() { return List.copyOf(tickets.values()); }
}
