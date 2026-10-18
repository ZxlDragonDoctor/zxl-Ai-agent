package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.conversation.ConversationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 执行权可由请求线程取得、工作线程释放；不使用跨线程解锁的 ReentrantLock。 */
@Component
public class ConversationRunGuard {
    private final ConcurrentHashMap<UUID, UUID> owners = new ConcurrentHashMap<>();

    public Lease acquire(UUID conversationId) {
        return tryAcquire(conversationId).orElseThrow(() -> new ConversationException(
                "CONVERSATION_BUSY", "此会话正在执行，请等待完成后重试", HttpStatus.CONFLICT));
    }

    public Optional<Lease> tryAcquire(UUID id) {
        UUID token = UUID.randomUUID();
        return owners.putIfAbsent(id, token) == null ? Optional.of(new Lease(id, token)) : Optional.empty();
    }

    public boolean isBusy(UUID id) { return owners.containsKey(id); }

    public final class Lease implements AutoCloseable {
        private final UUID id;
        private final UUID token;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(UUID id, UUID token) { this.id = id; this.token = token; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) owners.remove(id, token);
        }
    }
}
