package com.zxl.zxlaiagent.conversation.repository;

import com.zxl.zxlaiagent.conversation.model.Conversation;
import com.zxl.zxlaiagent.conversation.model.ConversationSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository {
    Conversation create(String title, String agentType);
    Optional<Conversation> find(UUID id);
    List<ConversationSummary> list();
    /** 传入读取时的 version；写入成功后持久化版本递增。 */
    void save(Conversation conversation);
    void delete(UUID id);
}
