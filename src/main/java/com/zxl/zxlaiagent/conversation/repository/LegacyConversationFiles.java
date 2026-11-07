package com.zxl.zxlaiagent.conversation.repository;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.zxl.zxlaiagent.conversation.model.Conversation;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** The digest and the decoded history always refer to the same bounded byte snapshot. */
public final class LegacyConversationFiles {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private final Path directory;
    private final ObjectReader reader;

    public LegacyConversationFiles(Path directory, ObjectMapper mapper) {
        this.directory = directory.toAbsolutePath().normalize();
        reader = mapper.readerFor(Conversation.class)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public Snapshot read(UUID id, String expectedSha256) {
        Objects.requireNonNull(id);
        if (!validSha256(expectedSha256)) throw new IllegalArgumentException("Invalid archive SHA-256");
        try {
            requireDirectory(directory);
            byte[] bytes = readBounded(directory.resolve(id + ".json"), MAX_BYTES);
            String digest = sha256(bytes);
            if (!expectedSha256.equals(digest)) throw new IllegalStateException("Archive digest mismatch");
            Conversation body = reader.readValue(bytes);
            if (body == null || !id.equals(body.id())) throw new IllegalStateException("Archive conversation ID mismatch");
            return new Snapshot(bytes, body, digest);
        } catch (IOException | SecurityException failure) {
            throw new IllegalStateException("Archive file cannot be read safely");
        }
    }

    public static void requireDirectory(Path path) throws IOException {
        Path directory = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath())) {
            throw new IOException("Archive directory is not a real directory");
        }
    }

    public static byte[] readBounded(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archive requires a regular file");
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var stream = Channels.newInputStream(channel)) {
            if (channel.size() > limit) throw new IOException("Archive file exceeds size limit");
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Archive file exceeds size limit");
            return bytes;
        }
    }

    public static boolean validSha256(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Snapshot(byte[] bytes, Conversation conversation, String sha256) {
        public Snapshot { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
