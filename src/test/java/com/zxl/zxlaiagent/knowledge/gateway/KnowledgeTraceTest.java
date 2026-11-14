package com.zxl.zxlaiagent.knowledge.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeTraceTest {
    @Test
    void validChildrenRetainTraceAndRestoreOuterContext() {
        String parent = KnowledgeTrace.fromRun("31b4a040-836f-43e8-8a15-e4de47cb4001");
        assertTrue(parent.startsWith("00-31b4a040836f43e88a15e4de47cb4001-"));
        String child = KnowledgeTrace.child(parent);
        assertEquals(parent.substring(0, 36), child.substring(0, 36));
        assertNotEquals(parent, child);
        try (var outer = KnowledgeTrace.open(parent)) {
            try (var nested = KnowledgeTrace.open(child)) {
                assertEquals(child, KnowledgeTrace.current());
            }
            assertEquals(parent, KnowledgeTrace.current());
        }
        assertNull(KnowledgeTrace.current());
    }

    @Test
    void malformedOrZeroIdsNeverEnterContext() {
        assertNull(KnowledgeTrace.valid("00-00000000000000000000000000000000-1111111111111111-01"));
        assertNull(KnowledgeTrace.valid("00-31b4a040836f43e88a15e4de47cb4001-0000000000000000-01"));
        assertNull(KnowledgeTrace.child("invalid"));
        try (var ignored = KnowledgeTrace.open("invalid")) {
            assertNull(KnowledgeTrace.current());
        }
    }
}
