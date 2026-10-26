package com.zxl.zxlaiagent.knowledge.mcp;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback SSE with synthetic backend; discovery stability is separate from PG/model quality. */
class KnowledgeMcpDiscoverySnapshotTest {
    @Test void concurrentRefreshCannotEraseAlreadyReturnedDiscoverySnapshot() throws Exception {
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->Map.of("hits",List.of())));
            var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()))) {
            var original=client.discover(Duration.ofSeconds(8),()->false);
            assertEquals(1,original.size());assertThrows(UnsupportedOperationException.class,()->original.clear());
            var pool=Executors.newFixedThreadPool(2);
            try {
                for(int round=0;round<4;round++) {
                    var start=new CountDownLatch(1);
                    var reading=pool.submit(()->{start.await();return client.discover(Duration.ofSeconds(8),()->false);});
                    var refreshing=pool.submit(()->{start.await();return client.refresh(Duration.ofSeconds(8),()->false);});
                    start.countDown();var selected=reading.get(10,TimeUnit.SECONDS);
                    assertEquals("READY",refreshing.get(10,TimeUnit.SECONDS).state());
                    assertEquals(original,selected);assertEquals(1,selected.size());
                }
                assertFalse(client.call(KnowledgeMcpClient.EXPOSED_NAME,Map.of("query","snapshot"),Duration.ofSeconds(2),()->false).isError());
            } finally {pool.shutdownNow();}
        }
    }
}
