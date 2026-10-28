package com.zxl.zxlaiagent.knowledge.gateway;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
class ScopedKnowledgeClientContractTest {
 @Test void arbitraryDestinationsAreRejected(){for(String uri:java.util.List.of("https://127.0.0.1:18132","http://localhost:18132","http://example.com:18132","http://127.0.0.1:18132/evil","http://user@127.0.0.1:18132","http://127.0.0.1:18132?x=1")){assertThrows(IllegalArgumentException.class,()->new ScopedKnowledgeClient(URI.create(uri),"abcdefghijklmnopqrstuvwxyz_0123456789-ABCDEFGHIJKLMNO",org.mockito.Mockito.mock(ServiceCallAuthorizer.class),org.mockito.Mockito.mock(AuthorizationService.class)));}}
 @Test void responseOwnsItsBytes(){byte[] body={1};var response=new ScopedKnowledgeClient.Response(200,body);body[0]=2;assertEquals(1,response.body()[0]);response.body()[0]=3;assertEquals(1,response.body()[0]);}
}
