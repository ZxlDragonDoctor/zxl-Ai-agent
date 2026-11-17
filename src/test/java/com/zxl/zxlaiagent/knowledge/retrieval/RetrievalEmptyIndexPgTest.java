package com.zxl.zxlaiagent.knowledge.retrieval;

import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalEmptyIndexPgTest {
    KnowledgeDatabase database() { return new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD")); }
    RetrievalService.Request request(String mode) { return new RetrievalService.Request("synthetic missing policy",mode,1,5,false,false,10000); }
    @Test void authoritativeEmptyTenantSkipsEmbeddingInVectorAndHybridModes() {
        var db=database();var calls=new AtomicInteger();
        KnowledgeEmbedding unavailable=text->{calls.incrementAndGet();throw new IllegalStateException("provider unavailable");};
        String tenant="empty_index_"+UUID.randomUUID().toString().replace("-","");
        for(String mode:List.of("hybrid","vector")) {
            var response=new RetrievalService(db,tenant,unavailable).retrieve(request(mode));
            assertEquals(0,calls.get());assertTrue(response.hits().isEmpty());assertTrue(response.degradations().isEmpty());
            assertEquals("NO_CURRENT_MATCHES",response.shortageReason());
            assertTrue(response.stages().stream().anyMatch(s->s.name().equals("vector")&&s.status().equals("SKIPPED_EMPTY_INDEX")));
            assertTrue(response.stages().stream().anyMatch(s->s.name().equals("final-visibility")));
        }
    }
    @Test void emptyTenantStillChecksPublicationAuthorization() {
        var db=database();var checks=new AtomicInteger();
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase phase){if(phase==Phase.RESULT_PUBLISH){checks.incrementAndGet();throw new SecurityFailure("ACTION_DENIED",403);}}
            public <T>T start(Phase phase,Supplier<T> action){check(phase);return action.get();}
        };
        var service=new RetrievalService(db,"empty_index_"+UUID.randomUUID(),new DemoKnowledgeEmbedding(),Reranker.rules(),boundary);
        assertThrows(SecurityFailure.class,()->service.retrieve(request("hybrid")));assertEquals(1,checks.get());
    }
}

