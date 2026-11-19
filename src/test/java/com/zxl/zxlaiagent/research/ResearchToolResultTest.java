package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResearchToolResultTest {
 @Test void adapterFailureRetainsOnlyItsSafeClassification()throws Exception {
  var json=new ObjectMapper();
  var result=ResearchToolResult.remoteFailure(json.readTree("{\"code\":\"MCP_INVALID_RESPONSE\",\"message\":\"private-payload\",\"data\":{\"private\":true}}"));
  assertEquals("MCP_INVALID_RESPONSE",ToolResults.read(result).path("code").asText());
  assertFalse(result.contains("private"));
  assertEquals("MCP_TOOL_ERROR",ToolResults.read(ResearchToolResult.remoteFailure(json.readTree("{\"code\":\"untrusted\"}"))).path("code").asText());
 }
 @Test void verifiedEmptyIndexStageRemainsVisible()throws Exception {
  var data=new ObjectMapper().readTree("{\"stages\":[{\"name\":\"vector\",\"status\":\"SKIPPED_EMPTY_INDEX\",\"elapsedMs\":0,\"candidateCount\":0}]}");
  assertEquals("SKIPPED_EMPTY_INDEX",ToolResults.read(ResearchToolResult.success(data,List.of())).path("data").path("stages").get(0).path("status").asText());
 }
}
