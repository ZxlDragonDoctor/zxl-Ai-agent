package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.multiagent.runtime.retry.RetryFailure;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 单 JVM 教学账本。锁内回调只允许短暂的本地原子写入，禁止远程 I/O。
 * 瞬态异常仅允许在确定尚无副作用时抛出；不确定失败保留记录，等待人工核对。
 * 不自动淘汰已完成记录，避免淘汰后同键再次写入；满容量后拒绝新操作。
 */
public final class OperationLedger<T> {
    private record Entry<T>(String fingerprint, T value, RetryFailure failure) {}
    private final Map<String, Entry<T>> entries = new HashMap<>();
    private final int capacity;
    public OperationLedger(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }
    public synchronized T execute(String operationId, String fingerprint, Supplier<T> localAtomicWrite) {
        var existing = entries.get(operationId);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw RetryFailure.permanentFailure("OPERATION_CONFLICT", "同一操作 ID 的业务参数不一致");
            if (existing.failure() != null) throw existing.failure();
            return existing.value();
        }
        if (entries.size() >= capacity) throw RetryFailure.permanentFailure("OPERATION_CAPACITY_EXCEEDED", "演示操作账本已满，请先完成持久化扩展");
        var uncertain = RetryFailure.permanentFailure("OPERATION_UNCERTAIN", "原操作结果不确定，禁止自动重新写入，需要核对业务结果");
        entries.put(operationId, new Entry<>(fingerprint, null, uncertain));
        try {
            T value = java.util.Objects.requireNonNull(localAtomicWrite.get());
            entries.put(operationId, new Entry<>(fingerprint, value, null));
            return value;
        } catch (RetryFailure known) {
            if (known.retryable()) entries.remove(operationId);
            else entries.put(operationId, new Entry<>(fingerprint, null, known));
            throw known;
        }
    }
}
