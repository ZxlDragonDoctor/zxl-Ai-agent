package com.zxl.zxlaiagent.research;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool;
import com.zxl.zxlaiagent.security.SecurityFailure;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ResearchDiagnosticsTest {
    final Logger logger=(Logger)LoggerFactory.getLogger(ResearchRunner.class);
    final ListAppender<ILoggingEvent> logs=new ListAppender<>();
    @BeforeEach void capture(){logs.start();logger.addAppender(logs);}
    @AfterEach void stop(){logger.detachAppender(logs);logs.stop();}
    ResearchRunner runner(Supplier<String> answer){
        var gateway=new ResearchRunner.Gateway(){
            public List<ResearchRunner.Tool> discover(Duration d,java.util.function.BooleanSupplier c){return List.of(new ResearchRunner.Tool("knowledge__knowledge_lookup_v1","lookup",KnowledgeLookupTool.SCHEMA));}
            public String call(String n,String args,Duration d,java.util.function.BooleanSupplier c){return "{\"success\":true,\"code\":\"OK\",\"message\":\"done\",\"data\":{\"hits\":[{\"documentId\":\"synthetic\",\"chunkId\":\"chunk\",\"text\":\"EVIDENCE_SECRET\"}]}}";}
        };
        var model=new ResearchRunner.Model(){
            public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> tools,Duration d,java.util.function.BooleanSupplier c){return new ResearchRunner.Selection("call",tools.get(0).name(),"{\"query\":\"synthetic\"}");}
            public String answer(String q,List<com.fasterxml.jackson.databind.JsonNode> hits,Duration d,java.util.function.BooleanSupplier c){return answer.get();}
        };
        return new ResearchRunner(gateway,model,(hits,d,c)->hits);
    }
    String last(){assertFalse(logs.list.isEmpty());var event=logs.list.get(logs.list.size()-1);assertNull(event.getThrowableProxy());return event.getFormattedMessage();}
    @Test void missingCitationHasFixedStageAndCodeWithoutBusinessData(){
        var failure=assertThrows(IllegalStateException.class,()->runner(()->"ANSWER_SECRET without citation").run("GOAL_SECRET",Duration.ofSeconds(2),()->false,event->{}));
        assertEquals("RESEARCH_MISSING_CITATION",failure.getMessage());String log=last();
        assertTrue(log.contains("stage=ANSWER_VALIDATION"));assertTrue(log.contains("code=RESEARCH_MISSING_CITATION"));assertTrue(log.contains("IllegalStateException"));
        assertFalse(log.contains("GOAL_SECRET"));assertFalse(log.contains("EVIDENCE_SECRET"));assertFalse(log.contains("ANSWER_SECRET"));
    }
    @Test void arbitraryNestedMessagesCannotBecomeCodesOrLogContent(){
        assertThrows(IllegalArgumentException.class,()->runner(()->{throw new IllegalArgumentException("PASSWORD_SECRET",new RuntimeException("TOKEN_SECRET"));}).run("GOAL_SECRET",Duration.ofSeconds(2),()->false,event->{}));
        String log=last();assertTrue(log.contains("stage=MODEL_ANSWER"));assertTrue(log.contains("code=RESEARCH_EXECUTION_FAILED"));
        assertTrue(log.contains("IllegalArgumentException"));assertTrue(log.contains("RuntimeException"));assertFalse(log.contains("PASSWORD_SECRET"));assertFalse(log.contains("TOKEN_SECRET"));
    }
    @Test void cancellationAndAuthorizationFailuresKeepTheirOriginalSemantics(){
        assertThrows(java.util.concurrent.CancellationException.class,()->runner(()->"unused").run("synthetic",Duration.ofSeconds(2),()->true,event->{}));
        assertTrue(last().contains("code=RESEARCH_CANCELLED"));
        var denied=new SecurityFailure("ACTION_DENIED",403);
        assertSame(denied,assertThrows(SecurityFailure.class,()->runner(()->{throw denied;}).run("synthetic",Duration.ofSeconds(2),()->false,event->{})));
        assertTrue(last().contains("code=ACTION_DENIED"));
    }
}
