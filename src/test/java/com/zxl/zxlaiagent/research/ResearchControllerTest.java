package com.zxl.zxlaiagent.research;
import com.zxl.zxlaiagent.knowledge.mcp.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResearchControllerTest {
 @Test void httpBoundaryRejectsDuplicateAndNonObjectInputBeforeAnyClientWork()throws Exception{
  var client=mock(KnowledgeMcpClient.class);
  when(client.call(anyString(),anyMap(),any(),any())).thenReturn(new McpSchema.CallToolResult(java.util.List.of(new McpSchema.TextContent("{\"success\":true,\"code\":\"OK\",\"message\":\"empty\",\"data\":{\"hits\":[]}}")),false));
  var mvc=MockMvcBuilders.standaloneSetup(new ResearchController(client)).build();
  for(String body:java.util.List.of("{\"query\":\"one\",\"query\":\"two\"}","null","[]","{\"query\":\"VPN\",\"tenantId\":\"other\"}")){
   mvc.perform(post("/ai/learning/mcp/lookup").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("MCP_INVALID_ARGUMENT"));
  }
  verifyNoInteractions(client);
 }
 @Test void unavailableDiscoveryIsExplicitWhileApplicationRemainsAvailable()throws Exception{
  var client=mock(KnowledgeMcpClient.class);
  when(client.status()).thenReturn(new KnowledgeMcpClient.Status("UNAVAILABLE","knowledge","http://127.0.0.1:18135",null,"MCP_NETWORK_FAILURE",0,0,0));when(client.tools()).thenReturn(java.util.List.of());
  when(client.refresh(any(),any())).thenThrow(new KnowledgeMcpClient.Failure("MCP_NETWORK_FAILURE"));
  MockMvcBuilders.standaloneSetup(new ResearchController(client)).build().perform(post("/ai/learning/mcp/refresh")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("UNAVAILABLE")).andExpect(jsonPath("$.lastError").value("MCP_NETWORK_FAILURE"));
 }
}
