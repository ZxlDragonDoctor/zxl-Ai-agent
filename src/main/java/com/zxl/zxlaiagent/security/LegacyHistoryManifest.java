package com.zxl.zxlaiagent.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.repository.LegacyConversationFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record LegacyHistoryManifest(String importId, String tenantId, String subjectId, List<Entry> entries) {
    public static final int MAX_ENTRIES = 100;

    public LegacyHistoryManifest {
        canonicalUuid(importId);
        if (!AuthenticationService.validId(tenantId) || !AuthenticationService.validId(subjectId)
                || entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid archive manifest");
        }
        var seen = new HashSet<String>();
        for (Entry entry : entries) {
            if (entry == null || !seen.add(entry.conversationId())) throw new IllegalArgumentException("Duplicate or missing archive entry");
        }
        entries = List.copyOf(entries);
    }

    public record Entry(String conversationId, String sha256) {
        public Entry {
            canonicalUuid(conversationId);
            if (!LegacyConversationFiles.validSha256(sha256)) throw new IllegalArgumentException("Invalid entry digest");
        }
    }

    static String canonicalUuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Canonical UUID required");
        return value;
    }

    public static Loaded load(Path path, ObjectMapper mapper) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            LegacyConversationFiles.requireDirectory(absolute.getParent());
            byte[] bytes = LegacyConversationFiles.readBounded(absolute, 64 * 1024);
            var reader = mapper.readerFor(LegacyHistoryManifest.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            LegacyHistoryManifest manifest = reader.readValue(bytes);
            if (manifest == null) throw new IllegalArgumentException("Manifest required");
            return new Loaded(manifest, LegacyConversationFiles.sha256(bytes));
        } catch (IOException | SecurityException failure) {
            throw new IllegalArgumentException("Archive manifest cannot be read safely");
        }
    }

    /** Only load() can bind a parsed manifest to its original digest. */
    public static final class Loaded {
        private final LegacyHistoryManifest manifest;
        private final String sha256;
        private Loaded(LegacyHistoryManifest manifest, String sha256) {
            this.manifest = manifest;
            this.sha256 = sha256;
        }
        public LegacyHistoryManifest manifest() { return manifest; }
        public String sha256() { return sha256; }
    }
}
