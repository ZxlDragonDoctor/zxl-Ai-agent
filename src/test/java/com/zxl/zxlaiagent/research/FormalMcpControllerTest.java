package com.zxl.zxlaiagent.research;

import com.zxl.zxlaiagent.knowledge.mcp.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.WebSecurityAdvice;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FormalMcpControllerTest {
    private static final AuthenticatedActor ACTOR = new AuthenticatedActor("subject", "tenant", "session", 1, "knowledge-maintainer", 1);
    private static final String ROOT = "/ai/learning/mcp";
    private AuthorizationService allowed() {
        var auth = mock(AuthorizationService.class);
        when(auth.decide(any(), anyString(), isNull(), any(), isNull())).thenReturn(new AuthorizationDecision("d", "test:1", true, "ALLOWED"));
        when(auth.gate(any(), anyString(), isNull(), any(), isNull(), any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(5)).get());
        return auth;
    }
    private MockMvc mvc(FormalKnowledgeMcpClient client, AuthorizationService authorization) throws Exception {
        var controller = Class.forName("com.zxl.zxlaiagent.research.FormalMcpController")
                .getConstructor(FormalKnowledgeMcpClient.class, AuthorizationService.class).newInstance(client, authorization);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebSecurityAdvice()).build();
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String suffix, String body) {
        return post(ROOT + suffix).requestAttr("com.zxl.zxlaiagent.security.web.AuthenticatedRequest.actor", ACTOR)
                .contentType("application/json").content(body);
    }
    @Test void anonymousStatusNeverReadsServiceMetadata() throws Exception {
        var client = mock(FormalKnowledgeMcpClient.class); var auth = allowed();
        mvc(client, auth).perform(get(ROOT + "/status")).andExpect(status().isUnauthorized()); verifyNoInteractions(client);
    }
    @Test void statusPublishesModelSchemaWithoutDelegationFields() throws Exception {
        var client = mock(FormalKnowledgeMcpClient.class);
        when(client.status()).thenReturn(new FormalKnowledgeMcpClient.Status("READY", "knowledge", "http://127.0.0.1:18135", "2024-11-05", null, 1, 0, 0));
        when(client.tools()).thenReturn(List.of(new FormalKnowledgeMcpClient.DiscoveredTool("knowledge", FormalKnowledgeMcpProtocol.NAME, FormalKnowledgeMcpProtocol.EXPOSED_NAME, "v2", "model-digest", "wire-digest", FormalKnowledgeMcpProtocol.modelDefinition())));
        mvc(client, allowed()).perform(get(ROOT + "/status").requestAttr("com.zxl.zxlaiagent.security.web.AuthenticatedRequest.actor", ACTOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tools[0].exposedName").value(FormalKnowledgeMcpProtocol.EXPOSED_NAME))
                .andExpect(jsonPath("$.tools[0].definition.inputSchema.properties.authorization").doesNotExist())
                .andExpect(jsonPath("$.tools[0].definition.inputSchema.properties.budgetMs").doesNotExist());
    }
    @Test void lookupSeparatesUserBudgetFromModelArgumentsAndCarriesActor() throws Exception {
        var client = mock(FormalKnowledgeMcpClient.class);
        when(client.call(eq(ACTOR.ref()), eq(FormalKnowledgeMcpProtocol.EXPOSED_NAME), eq(Map.of("query", "VPN", "topK", 3, "mode", "keyword")), eq(Duration.ofMillis(1200)), any()))
                .thenReturn(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(ToolResults.json(ToolResult.ok("done", Map.of("hits", List.of()))))), false));
        mvc(client, allowed()).perform(request("/lookup", "{\"query\":\"VPN\",\"topK\":3,\"mode\":\"keyword\",\"budgetMs\":1200}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.hits").isArray());
        verify(client).call(eq(ACTOR.ref()), eq(FormalKnowledgeMcpProtocol.EXPOSED_NAME), anyMap(), eq(Duration.ofMillis(1200)), any());
    }
    @Test void forgedIdentityMalformedAndUnboundedBodiesNeverDispatch() throws Exception {
        var client = mock(FormalKnowledgeMcpClient.class); var mvc = mvc(client, allowed());
        for (String body : List.of("{\"query\":\"VPN\",\"profile\":\"alice\"}", "{\"query\":\"VPN\",\"authorization\":\"forged\"}", "{\"query\":\"VPN\",\"topK\":3.0}", "{\"query\":\"VPN\",\"budgetMs\":10001}", "{\"query\":\"a\",\"query\":\"b\"}", "{} {}", "{\"query\":\"" + "a".repeat(17000) + "\"}"))
            mvc.perform(request("/lookup", body)).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }
    @Test void refreshChecksCurrentManagePermissionBeforeClient() throws Exception {
        var client = mock(FormalKnowledgeMcpClient.class); var authorization = allowed();
        when(authorization.decide(any(), eq("knowledge.manage"), isNull(), any(), isNull())).thenReturn(new AuthorizationDecision("d", "test:1", false, "ACTION_DENIED"));
        mvc(client, authorization).perform(request("/refresh", "{}")).andExpect(status().isForbidden()); verifyNoInteractions(client);
    }
    @Test void failedServiceIsNotReportedAsSuccessfulEmptyRetrieval() throws Exception {
        var client = mock(FormalKnowledgeMcpClient.class);
        when(client.call(any(), anyString(), anyMap(), any(), any())).thenThrow(new FormalKnowledgeMcpClient.Failure("MCP_NETWORK_FAILURE"));
        mvc(client, allowed()).perform(request("/lookup", "{\"query\":\"VPN\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MCP_NETWORK_FAILURE"));
    }
}
