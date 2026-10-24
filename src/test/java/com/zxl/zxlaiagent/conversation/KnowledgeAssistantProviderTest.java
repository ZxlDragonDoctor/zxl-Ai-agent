package com.zxl.zxlaiagent.conversation;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.agent.KnowledgeAssistantProvider;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAssistantProviderTest {
    @Test void registersOneTypeAndPersistsStructuredEvidenceAlongsideAnswer() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var bodies=new CopyOnWriteArrayList<String>();
        server.createContext("/answers", exchange->{
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"traceId\":\"t1\",\"snapshotId\":\"s1\",\"status\":\"ANSWERED\",\"answer\":\"检查网络 [E1]\",\"claims\":[],\"evidence\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var provider=new KnowledgeAssistantProvider(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/answers"),"demo");
            assertEquals("enterprise-knowledge",provider.definition().id());
            var agent=provider.create();agent.setConversationId("conversation-a");
            var events=new CopyOnWriteArrayList<com.zxl.zxlaiagent.agent.model.AgentExecutionEvent>();agent.setEventObserver(events::add);
            assertTrue(agent.run("VPN809怎么办").contains("检查网络"));
            assertEquals(1,events.stream().filter(e->"KNOWLEDGE_RESULT".equals(e.type())).count());
            var request=new ObjectMapper().readTree(bodies.get(0));
            assertEquals("conversation-a",request.path("conversationId").asText());
            assertEquals("alice",request.path("profile").asText());
            assertFalse(request.has("tenant"));
            assertEquals("assistant",agent.getMessageList().get(agent.getMessageList().size()-1).getMessageType().getValue());
        } finally {server.stop(0);}
    }
    @Test void rejectsRemoteGatewayAndUnknownMode() {
        assertThrows(IllegalArgumentException.class,()->new KnowledgeAssistantProvider(URI.create("https://example.com/answers"),"demo"));
        assertThrows(IllegalArgumentException.class,()->new KnowledgeAssistantProvider(URI.create("http://127.0.0.1:18132/answers"),"pretend-real"));
    }
    @Test void serviceFailureDoesNotBecomeSuccessfulEmptyAnswer() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/answers",exchange->{byte[] body="{\"traceId\":\"t\",\"snapshotId\":\"s\",\"status\":\"SERVICE_FAILURE\",\"answer\":\"\",\"claims\":[],\"evidence\":[]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {var agent=new KnowledgeAssistantProvider(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/answers"),"demo").create();agent.run("VPN809");assertTrue(agent.getEndReason().isFailure());}
        finally {server.stop(0);}
    }
}
