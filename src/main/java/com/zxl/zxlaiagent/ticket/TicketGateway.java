package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.multiagent.runtime.ExecutionControl;

/** 下游必须对相同 operationId 实施去重；未知远程写入结果不可简单转成可重试错误。 */
@FunctionalInterface
public interface TicketGateway {
    Ticket create(TicketCommand command, ExecutionControl control);
}
