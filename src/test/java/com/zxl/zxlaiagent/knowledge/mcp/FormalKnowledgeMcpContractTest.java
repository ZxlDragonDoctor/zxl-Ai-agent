package com.zxl.zxlaiagent.knowledge.mcp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FormalKnowledgeMcpContractTest {
 @Test void formalVersionMustExistAndSeparateModelAndWireSchemas() throws Exception {
  Class<?> type=Class.forName("com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpProtocol");
  String model=(String)type.getField("MODEL_SCHEMA").get(null);
  String wire=(String)type.getField("WIRE_SCHEMA").get(null);
  assertFalse(model.contains("authorization")); assertFalse(model.contains("budgetMs"));
  assertTrue(wire.contains("authorization")); assertTrue(wire.contains("budgetMs"));
  assertEquals("knowledge_lookup_v2",type.getField("NAME").get(null));
 }
 @Test void canonicalHashNormalizesOrderingAndIntegralRepresentationsButBindsBudget(){
  var left=new LinkedHashMap<String,Object>();left.put("query","企业VPN");left.put("topK",3);left.put("mode","keyword");left.put("budgetMs",1000);
  var right=new LinkedHashMap<String,Object>();right.put("budgetMs",1000L);right.put("mode","keyword");right.put("topK",3L);right.put("query","企业VPN");
  assertEquals(FormalKnowledgeMcpProtocol.requestHash(left),FormalKnowledgeMcpProtocol.requestHash(right));
  right.put("budgetMs",1001L);assertNotEquals(FormalKnowledgeMcpProtocol.requestHash(left),FormalKnowledgeMcpProtocol.requestHash(right));
  right.put("authorization","not-part-of-business-contract");assertThrows(IllegalArgumentException.class,()->FormalKnowledgeMcpProtocol.requestHash(right));
 }
 @Test void modelCannotProvideReservedInfrastructureFields(){
  for(String field:List.of("budgetMs","authorization","tenant","actor","sessionId"))assertThrows(IllegalArgumentException.class,()->FormalKnowledgeMcpProtocol.modelArguments(Map.of("query","x",field,"forged")));
  assertThrows(IllegalArgumentException.class,()->FormalKnowledgeMcpProtocol.modelArguments(Map.of("query","x","topK",3.0)));
  assertThrows(IllegalArgumentException.class,()->FormalKnowledgeMcpProtocol.modelArguments(Map.of("query","x".repeat(2001))));
 }
 @Test void missingInfrastructureSecretFailsClosed(){for(String token:Arrays.asList(null,"","short","x".repeat(257),"x".repeat(32)+"\r\n"))assertThrows(IllegalArgumentException.class,()->InternalServiceHttpClient.requireToken(token));}
 public static void main(String[] args)throws Exception {var test=new FormalKnowledgeMcpContractTest();test.formalVersionMustExistAndSeparateModelAndWireSchemas();test.canonicalHashNormalizesOrderingAndIntegralRepresentationsButBindsBudget();test.modelCannotProvideReservedInfrastructureFields();test.missingInfrastructureSecretFailsClosed();System.out.println("FORMAL_MCP_CONTRACT_PROBE_PASS=4");}
}
