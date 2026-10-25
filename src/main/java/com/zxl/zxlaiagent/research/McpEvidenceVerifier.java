package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Independent application-side fixed-scope source lookup. Remote MCP identifiers are never authorization. */
public final class McpEvidenceVerifier implements ResearchRunner.Verifier {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final URI base;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public McpEvidenceVerifier(URI base){
        if(base==null||!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getPort()<1||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!"/ai/learning/knowledge/documents/".equals(base.getPath()))throw new IllegalArgumentException("Evidence gateway must be a fixed loopback document endpoint");
        this.base=base;
    }
    @Override public List<JsonNode> verify(List<JsonNode> hits,Duration budget,BooleanSupplier cancelled){
        long end=System.nanoTime()+budget.toNanos();Map<String,JsonNode> docs=new HashMap<>();
        if(hits.size()>5)throw new IllegalStateException("EVIDENCE_LIMIT");
        for(var hit:hits){
            String id=hit.path("documentId").asText();
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))throw new IllegalStateException("EVIDENCE_DOCUMENT_ID");
            JsonNode detail=docs.computeIfAbsent(id,key->fetch(base.resolve(key),end,cancelled));
            var document=detail.path("document");var raw=detail.path("rawText");String text=hit.path("text").asText();
            int start=hit.path("startUtf16").asInt(-1),stop=hit.path("endUtf16").asInt(-1);
            if(!id.equals(document.path("documentId").asText())||!"ACTIVE".equals(document.path("status").asText())
                    ||!document.path("activeRevision").asText().equals(hit.path("revisionId").asText())||document.path("version").asLong(-1)!=hit.path("generation").asLong(-2)
                    ||!raw.isTextual()||text.isBlank()||text.length()>4000||start<0||stop<=start||stop>raw.asText().length()||!raw.asText().substring(start,stop).equals(text)
                    ||line(raw.asText(),start)!=hit.path("startLine").asInt(-1)||line(raw.asText(),stop-1)!=hit.path("endLine").asInt(-1))throw new IllegalStateException("EVIDENCE_NOT_CURRENT_OR_VISIBLE");
            boolean chunk=false;for(var c:detail.path("chunks"))if(c.path("chunkId").asText().equals(hit.path("chunkId").asText())&&c.path("text").asText().equals(text)&&c.path("startUtf16").asInt(-1)==start&&c.path("endUtf16").asInt(-1)==stop)chunk=true;
            if(!chunk)throw new IllegalStateException("EVIDENCE_CHUNK_MISMATCH");
            if(com.zxl.zxlaiagent.knowledge.answer.AnswerService.untrustedSource(text))throw new IllegalStateException("EVIDENCE_UNTRUSTED_INSTRUCTION");
        }
        ResearchRunner.remaining(end,cancelled);return List.copyOf(hits);
    }
    private JsonNode fetch(URI uri,long end,BooleanSupplier cancelled){
        var req=HttpRequest.newBuilder(uri).timeout(ResearchRunner.remaining(end,cancelled)).GET().build();
        var f=http.sendAsync(req,HttpResponse.BodyHandlers.ofByteArray());
        try{
            HttpResponse<byte[]> response;
            while(true){ResearchRunner.remaining(end,cancelled);try{response=f.get(50,TimeUnit.MILLISECONDS);break;}catch(TimeoutException poll){}}
            // Post-allocation limit: this loopback teaching adapter is not a streaming HTTP memory firewall.
            if(response.statusCode()!=200||response.body().length>1_048_576)throw new IllegalStateException("EVIDENCE_GATEWAY_UNAVAILABLE");
            return JSON.readTree(response.body());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("EVIDENCE_CANCELLED");}
        catch(Exception e){throw new IllegalStateException("EVIDENCE_GATEWAY_FAILED",e);}
        finally{if(!f.isDone())f.cancel(true);}
    }
    private static int line(String raw,int at){int line=1;for(int i=0;i<at;i++)if(raw.charAt(i)=='\n')line++;return line;}
}
