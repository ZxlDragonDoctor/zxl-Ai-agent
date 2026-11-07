package com.zxl.zxlaiagent.multiagent.security;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/ai/multiagent")
@ConditionalOnProperty(name="agent.security.mode",havingValue="required")
public final class FormalMultiAgentController {
    private static final int MAX_BYTES=131072;
    private final ScopedMultiAgentService service;
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public FormalMultiAgentController(ScopedMultiAgentService service){this.service=service;}
    @PostMapping(value="/runs",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,String>> submit(HttpServletRequest request){
        var actor=AuthenticatedRequest.require(request).ref();JsonNode body=read(request);
        String goal=body.path("goal").asText();Long timeout=body.hasNonNull("timeoutMs")?body.path("timeoutMs").longValue():null;
        Map<String,Object> context=body.hasNonNull("context")?json.convertValue(body.get("context"),new TypeReference<>(){}):Map.of();
        return safe(()->ResponseEntity.accepted().body(Map.of("executionId",service.submit(actor,goal,context,timeout))));
    }
    @GetMapping("/runs/{executionId}")
    public Object detail(HttpServletRequest request,@PathVariable String executionId){var actor=AuthenticatedRequest.require(request).ref();return safe(()->service.detail(actor,executionId));}
    @PostMapping("/runs/{executionId}/cancel")
    public ScopedMultiAgentService.CancelAcknowledgement cancel(HttpServletRequest request,@PathVariable String executionId){var actor=AuthenticatedRequest.require(request).ref();return safe(()->service.cancel(actor,executionId));}
    @GetMapping("/status")
    public ScopedMultiAgentService.WorkspaceRuns status(HttpServletRequest request){var actor=AuthenticatedRequest.require(request).ref();return safe(()->service.status(actor));}
    private JsonNode read(HttpServletRequest request){
        try{
            if(request.getContentLengthLong()>MAX_BYTES)throw invalid();
            byte[] bytes=request.getInputStream().readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw invalid();
            var body=json.readTree(bytes);if(body==null||!body.isObject())throw invalid();
            body.fieldNames().forEachRemaining(name->{if(!Set.of("goal","context","timeoutMs").contains(name))throw invalid();});
            if(!body.path("goal").isTextual()||body.path("goal").asText().isBlank()||body.path("goal").asText().length()>12000)throw invalid();
            if(body.hasNonNull("context")&&!body.get("context").isObject())throw invalid();
            if(body.hasNonNull("timeoutMs")&&(!body.get("timeoutMs").isIntegralNumber()||!body.get("timeoutMs").canConvertToLong()||body.get("timeoutMs").longValue()<1))throw invalid();
            return body;
        }catch(SecurityFailure known){throw known;}catch(Exception malformed){throw invalid();}
    }
    private static SecurityFailure invalid(){return new SecurityFailure("INVALID_RUN_REQUEST",400);}
    private static <T>T safe(Supplier<T> action){
        try{return action.get();}catch(SecurityFailure known){throw known;}
        catch(WorkflowException failure){throw new SecurityFailure(failure.getCode(),failure.getCode().startsWith("CAPACITY")||failure.getCode().equals("AGENT_CAPACITY_EXCEEDED")?429:400);}
        catch(RuntimeException unavailable){throw new SecurityFailure("RUN_SERVICE_UNAVAILABLE",503);}
    }
}
