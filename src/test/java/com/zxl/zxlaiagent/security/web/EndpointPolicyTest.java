package com.zxl.zxlaiagent.security.web;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EndpointPolicyTest {
    @Test void contextPathIsRemovedAtASegmentBoundaryAndAlternateEncodingsFailClosed() {
        assertEquals("/conversations",EndpointPolicy.servletPath("/api","/api/conversations"));
        assertEquals("",EndpointPolicy.servletPath("/api","/api-extra/conversations"));
        for(String path:List.of("/ai//agent/chat","/ai/%61gent/chat","/conversations;tenant=x","/ai/../image","/a\\b"))
            assertTrue(EndpointPolicy.classify("POST",path).blocked(),path);
    }
    @Test void formalModeClosesUnownedGlobalAndLegacyEntrypoints() {
        for(String path:List.of("/ai/agent/chat","/ai/manus/chat","/ai/love_app/chat/sync","/ai/love_app/chat/sse",
                "/ai/multiagent/orchestrate/stream","/ai/multiagent/events","/ai/multiagent/agents",
                "/ai/multiagent/harness/research-1","/ai/multiagent/harness/benchmark","/image","/image/multipleConditions",
                "/ai/learning/tickets","/ai/learning/persistent-runs","/v3/api-docs","/new-controller"))
            assertTrue(EndpointPolicy.classify("GET",path).blocked(),path);
        assertTrue(EndpointPolicy.classify("POST","/ai/multiagent/agents/research-1/message").blocked());
        assertTrue(EndpointPolicy.classify("POST","/ai/learning/knowledge/evaluation/run").blocked());
    }
    @Test void onlyExplicitPublicEntriesBypassAuthenticationAndLoginStillRequiresCsrf() {
        assertTrue(EndpointPolicy.classify("GET","/auth/status").publicAccess());
        assertTrue(EndpointPolicy.classify("POST","/auth/login").publicAccess());
        assertTrue(EndpointPolicy.classify("POST","/auth/login").csrf());
        assertFalse(EndpointPolicy.classify("POST","/auth/logout").publicAccess());
        assertTrue(EndpointPolicy.classify("PUT","/auth/login").blocked());
    }
    @Test void resourceRoutesRetainOwnershipRequirementsAndMutationsRequireCsrf() {
        String id="12345678-1234-1234-1234-123456789abc";
        var read=EndpointPolicy.classify("GET","/conversations/"+id);
        assertEquals("conversation.readOwn",read.action());assertEquals(id,read.resourceId());assertFalse(read.csrf());
        assertEquals("run.writeOwn",EndpointPolicy.classify("POST","/ai/multiagent/runs/"+id+"/cancel").action());
        assertEquals("knowledge.manage",EndpointPolicy.classify("DELETE","/ai/learning/knowledge/documents/vpn").action());
        assertTrue(EndpointPolicy.classify("POST","/ai/agent/chat").csrf());
        assertTrue(EndpointPolicy.classify("DELETE","/ai/learning/knowledge/documents/vpn").csrf());
        assertTrue(EndpointPolicy.classify("GET","/conversations/not-a-uuid").blocked());
        assertEquals("knowledge.read",EndpointPolicy.classify("GET","/ai/learning/knowledge/documents/vpn..guide").action());
    }
}
