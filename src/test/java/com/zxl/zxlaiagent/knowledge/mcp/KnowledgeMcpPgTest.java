package com.zxl.zxlaiagent.knowledge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.knowledge.retrieval.*;
import com.zxl.zxlaiagent.tools.support.StructuredMcpToolCallback;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL + pgvector behind actual SDK SSE; test embedding is explicit, no paid model call. */
class KnowledgeMcpPgTest {
    KnowledgeDatabase db;String tenant;KnowledgeService ingestion;
    @BeforeEach void setup(){
        db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        tenant="mcp_"+UUID.randomUUID().toString().replace("-","");
        ingestion=new KnowledgeService(db,tenant,new DemoKnowledgeEmbedding());
    }
    @AfterEach void cleanup(){if(db!=null&&tenant!=null)db.tx(c->{
        for(String table:List.of("knowledge_chunk","knowledge_revision","knowledge_document"))try(var s=c.prepareStatement("DELETE FROM "+table+" WHERE tenant_id=? OR tenant_id=?")){s.setString(1,tenant);s.setString(2,tenant+"other");s.executeUpdate();}return null;});}
    @Test void realMcpQueriesOnlyCurrentAuthorizedPgRevisionsAndSeparatesEmpty() throws Exception {
        String raw="# VPN 809\n\nVPN 809 检查 UDP 500 与4500端口。";
        ingestion.importDocument("vpn","vpn.md",raw);
        new KnowledgeService(db,tenant+"other",new DemoKnowledgeEmbedding()).importDocument("secret","secret.md","VPN 809 tenant secret");
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(new RetrievalService(db,tenant,new DemoKnowledgeEmbedding())));
            var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()))) {
            client.refresh(Duration.ofSeconds(5),()->false);
            var first=query(client,"809");assertTrue(first.path("success").asBoolean());
            assertEquals(1,first.path("data").path("hits").size());
            var hit=first.path("data").path("hits").get(0);
            assertEquals("vpn",hit.path("documentId").asText());
            assertEquals(raw.substring(hit.path("startUtf16").asInt(),hit.path("endUtf16").asInt()),hit.path("text").asText());
            assertTrue(hit.path("generation").asLong()>0);assertFalse(first.toString().contains("tenant secret"));
            assertTrue(query(client,"notfoundzzxyz7654").path("data").path("hits").isEmpty());
            ingestion.delete("vpn");assertTrue(query(client,"809").path("data").path("hits").isEmpty());
            ingestion.importDocument("vpn","vpn.md",raw);
            var replacement=query(client,"809").path("data").path("hits").get(0);
            assertEquals(hit.path("revisionId"),replacement.path("revisionId"));
            assertTrue(replacement.path("generation").asLong()>hit.path("generation").asLong(),"Same-content reimport is a distinct generation");
            ingestion.importDocument("vpn","vpn.md","# VPN 401\n\n401 请刷新 SSO 凭据。");
            assertTrue(query(client,"809").path("data").path("hits").isEmpty());
            assertFalse(query(client,"401").path("data").path("hits").isEmpty());
        }
    }
    private JsonNode query(KnowledgeMcpClient client,String query){return ToolResults.read(StructuredMcpToolCallback.adaptResult(client.call(KnowledgeMcpClient.EXPOSED_NAME,
            Map.of("query",query,"mode","keyword"),Duration.ofSeconds(5),()->false)));}
}
