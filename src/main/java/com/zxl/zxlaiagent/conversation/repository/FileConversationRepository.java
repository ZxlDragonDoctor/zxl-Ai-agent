package com.zxl.zxlaiagent.conversation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.ConversationException;
import com.zxl.zxlaiagent.conversation.model.Conversation;
import com.zxl.zxlaiagent.conversation.model.ConversationSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

/** 单进程文件仓储。短文件操作锁不跨越模型执行；多实例应迁移至数据库。 */
@Repository
public class FileConversationRepository implements ConversationRepository {
    private final Path directory;
    private final ObjectMapper mapper;
    private final Object[] locks = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public FileConversationRepository(ObjectMapper mapper,
            @Value("${agent.conversation.storage-dir:runtime-data/conversations}") String directory) {
        this.mapper = mapper;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public Conversation create(String title, String agentType) {
        return create(UUID.randomUUID(), title, agentType);
    }

    @Override
    public Conversation create(UUID id, String title, String agentType) {
        Objects.requireNonNull(id, "id");
        Instant now = Instant.now();
        Conversation conversation = new Conversation(id, title, now, now, 0, List.of(), List.of(), agentType);
        synchronized (lock(id)) {
            if (Files.exists(path(id))) throw new ConversationException("CONVERSATION_CONFLICT", "会话已存在", HttpStatus.CONFLICT);
            write(conversation);
        }
        return conversation;
    }

    @Override
    public Optional<Conversation> find(UUID id) {
        synchronized (lock(id)) {
            try {
                Conversation result = mapper.readValue(Files.readAllBytes(path(id)), Conversation.class);
                if (result == null || !id.equals(result.id())) throw new IllegalArgumentException("会话 ID 不匹配");
                return Optional.of(result);
            } catch (NoSuchFileException e) {
                return Optional.empty();
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new ConversationException("CONVERSATION_CORRUPT", "会话文件损坏，无法恢复，请检查服务端存储", HttpStatus.INTERNAL_SERVER_ERROR, e);
            } catch (IOException e) {
                throw new ConversationException("CONVERSATION_READ_FAILED", "读取会话失败，请检查存储权限", HttpStatus.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    @Override
    public List<ConversationSummary> list() {
        try {
            Files.createDirectories(directory);
            List<ConversationSummary> summaries = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    UUID id;
                    try { id = UUID.fromString(name.substring(0, name.length() - 5)); }
                    catch (IllegalArgumentException e) {
                        throw new ConversationException("CONVERSATION_CORRUPT", "会话目录存在无效的 JSON 文件名", HttpStatus.INTERNAL_SERVER_ERROR, e);
                    }
                    find(id).ifPresent(c -> summaries.add(c.summary(false)));
                }
            }
            summaries.sort(Comparator.comparing(ConversationSummary::updatedAt).reversed());
            return summaries;
        } catch (IOException e) {
            throw new ConversationException("CONVERSATION_LIST_FAILED", "无法列出会话，请检查存储目录", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    @Override
    public void save(Conversation conversation) {
        synchronized (lock(conversation.id())) {
            Conversation current = find(conversation.id()).orElseThrow(FileConversationRepository::missing);
            if (current.version() != conversation.version()) {
                throw new ConversationException("CONVERSATION_CONFLICT", "会话已更新，请重新读取后再操作", HttpStatus.CONFLICT);
            }
            write(new Conversation(conversation.id(), conversation.title(), conversation.createdAt(),
                    conversation.updatedAt(), conversation.version() + 1, conversation.messages(), conversation.runs(), conversation.agentType()));
        }
    }

    @Override
    public void delete(UUID id) {
        synchronized (lock(id)) {
            try { Files.delete(path(id)); }
            catch (NoSuchFileException e) { throw missing(); }
            catch (IOException e) {
                throw new ConversationException("CONVERSATION_DELETE_FAILED", "删除会话失败，请检查存储权限", HttpStatus.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    private void write(Conversation conversation) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(conversation);
            temporary = Files.createTempFile(directory, conversation.id() + ".", ".tmp");
            Files.write(temporary, json);
            Files.move(temporary, path(conversation.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ConversationException("CONVERSATION_WRITE_FAILED", "会话保存失败，存储目录必须可写并支持原子替换", HttpStatus.INTERNAL_SERVER_ERROR, e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* 原写入异常保留；遗留 .tmp 不会作为会话加载。 */ }
            }
        }
    }

    private Path path(UUID id) { return directory.resolve(Objects.requireNonNull(id) + ".json"); }
    private Object lock(UUID id) { return locks[Math.floorMod(id.hashCode(), locks.length)]; }
    private static ConversationException missing() {
        return new ConversationException("CONVERSATION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND);
    }
}
