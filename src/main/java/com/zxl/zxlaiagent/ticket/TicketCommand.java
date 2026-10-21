package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.multiagent.runtime.retry.RetryFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record TicketCommand(String operationId, String title, String description) {
    public TicketCommand {
        operationId = clean(operationId); title = clean(title); description = clean(description);
        if (operationId.isEmpty() || operationId.length() > 128 || title.isEmpty() || title.length() > 200 || description.length() > 10_000) {
            throw RetryFailure.permanentFailure("INVALID_TICKET", "操作 ID 与工单标题必填，参数长度不得超过演示限制");
        }
    }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    public String fingerprint() {
        // 长度前缀避免不同字段拼接产生歧义；规范版本进入摘要，之后变更规则须迁移。
        String canonical = "ticket-v1:" + title.length() + ":" + title + description.length() + ":" + description;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
