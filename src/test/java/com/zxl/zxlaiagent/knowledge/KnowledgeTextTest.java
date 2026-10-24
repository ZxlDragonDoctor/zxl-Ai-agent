package com.zxl.zxlaiagent.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeTextTest {
    @Test void stableIdentityAndOriginalUnicodeCoordinates() {
        String raw="# VPN\r\n\r\n中文😀重复\r\n\r\n中文😀重复\r\n";
        var a=KnowledgeText.prepare("tenant-a","vpn","vpn.md",raw,24);
        var b=KnowledgeText.prepare("tenant-a","vpn","vpn.md",raw,24);
        assertEquals(a.revisionId(),b.revisionId());
        assertNotEquals(a.revisionId(),KnowledgeText.prepare("tenant-b","vpn","vpn.md",raw,24).revisionId());
        for(var c:a.chunks()) {
            assertEquals(c.text(),raw.substring(c.startUtf16(),c.endUtf16()));
            assertFalse(Character.isLowSurrogate(raw.charAt(c.startUtf16())));
            assertTrue(c.endLine()>=c.startLine());
        }
        assertTrue(a.chunks().size()>1);
    }
    @Test void contentAndProfileChangeRevision() {
        var a=KnowledgeText.prepare("a","vpn","vpn.md","# VPN\nhello",80);
        assertNotEquals(a.revisionId(),KnowledgeText.prepare("a","vpn","vpn.md","# VPN\nchanged",80).revisionId());
        assertNotEquals(a.revisionId(),KnowledgeText.prepare("a","vpn","vpn.md","# VPN\nhello",81).revisionId());
    }
    @Test void rejectsUnsafeOrUnsupportedInputs() {
        assertThrows(IllegalArgumentException.class,()->KnowledgeText.prepare("a","../vpn","vpn.md","hi",80));
        assertThrows(IllegalArgumentException.class,()->KnowledgeText.prepare("a","vpn","vpn.pdf","hi",80));
        assertThrows(IllegalArgumentException.class,()->KnowledgeText.prepare("a","vpn","vpn.md","  ",80));
        assertThrows(IllegalArgumentException.class,()->KnowledgeText.prepare("a","vpn","vpn.md","x".repeat(100001),80));
    }
    @Test void deterministicEmbeddingHasFixedFiniteDimension() {
        var e=new DemoKnowledgeEmbedding();
        assertArrayEquals(e.embed("VPN"),e.embed("VPN"));
        assertEquals(8,e.embed("VPN").length);
        assertThrows(IllegalArgumentException.class,()->KnowledgeEmbedding.vectorLiteral(new float[]{Float.NaN}));
        assertThrows(IllegalArgumentException.class,()->KnowledgeEmbedding.vectorLiteral(new float[]{0,0,0,0,0,0,0,0}));
    }
    @Test void standaloneCarriageReturnsCountAsSourceLines(){
        var p=KnowledgeText.prepare("a","vpn","vpn.md","# Title\r12345678901234567890\rnext line",16);
        assertTrue(p.chunks().stream().anyMatch(c->c.startLine()>1));
    }
    @Test void headingsCreateBoundariesAndRetainHierarchy(){
        var p=KnowledgeText.prepare("a","vpn","vpn.md","# VPN\n\n介绍\n\n## 账号\n\n步骤\n\n### 重置\n重置密码",400);
        assertEquals(3,p.chunks().size());
        assertEquals("VPN",p.chunks().get(0).titlePath());
        assertEquals("VPN / 账号",p.chunks().get(1).titlePath());
        assertEquals("VPN / 账号 / 重置",p.chunks().get(2).titlePath());
        assertFalse(p.chunks().get(0).text().contains("账号"));
    }
    @Test void fencedCodeCannotCreateFakeHeadings(){
        String raw="# VPN\n\n```shell\n# fake\n```\n\nfinish";
        var p=KnowledgeText.prepare("a","vpn","vpn.md",raw,400);
        assertEquals(1,p.chunks().size());assertEquals("VPN",p.chunks().get(0).titlePath());
    }
}
