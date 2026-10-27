package com.zxl.zxlaiagent.conversation.repository;

import com.zxl.zxlaiagent.conversation.model.Conversation;
import com.zxl.zxlaiagent.conversation.model.ConversationSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository {
    default Conversation create(String title) { return create(title, "manus"); }
    Conversation create(String title, String agentType);
    /** A trusted owner index reserves this UUID before the first body is written. */
    default Conversation create(UUID id, String title, String agentType) {
        throw new IllegalStateException("SCOPED_CREATION_UNSUPPORTED");
    }
    Optional<Conversation> find(UUID id);
    List<ConversationSummary> list();
    /** 传入读取时的 version；写入成功后持久化版本递增。 */
    void save(Conversation conversation);
    void delete(UUID id);
}
