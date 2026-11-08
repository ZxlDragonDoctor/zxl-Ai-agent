package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Independent application-side fixed-scope source lookup. Remote MCP identifiers are never authorization. */
public final class McpEvidenceVerifier implements ResearchRunner.Verifier {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_EVIDENCE_BYTES = 1_048_576;
    @FunctionalInterface public interface VerificationPort {JsonNode document(String documentId,Duration budget,BooleanSupplier cancelled);}
    private final URI base;private final VerificationPort port;private final OperationBoundary boundary;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public McpEvidenceVerifier(URI base){
        if(base==null||!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getPort()<1||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!"/ai/learning/knowledge/documents/".equals(base.getPath()))throw new IllegalArgumentException("Evidence gateway must be a fixed loopback document endpoint");
        this.base=base;this.port=null;this.boundary=OperationBoundary.unrestricted();
    }
    /** Formal caller injects a ticket-authenticated, actor-bound document port; no anonymous HTTP fallback. */
    public McpEvidenceVerifier(VerificationPort port,OperationBoundary boundary){this.port=Objects.requireNonNull(port);this.boundary=Objects.requireNonNull(boundary);this.base=null;}
    @Override public List<JsonNode> verify(List<JsonNode> hits,Duration budget,BooleanSupplier cancelled){
        long end=System.nanoTime()+budget.toNanos();Map<String,JsonNode> docs=new HashMap<>();
        if(hits.size()>5)throw new IllegalStateException("EVIDENCE_LIMIT");
        for(var hit:hits){
            String id=hit.path("documentId").asText();
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))throw new IllegalStateException("EVIDENCE_DOCUMENT_ID");
            JsonNode detail=docs.computeIfAbsent(id,key->fetchDocument(key,end,cancelled));
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
    private JsonNode fetchDocument(String id,long end,BooleanSupplier cancelled){
        boundary.check(Phase.SOURCE_READ);
        JsonNode result=port==null?fetch(base.resolve(id),end,cancelled):port.document(id,ResearchRunner.remaining(end,cancelled),cancelled);
        ResearchRunner.remaining(end,cancelled);boundary.check(Phase.EVIDENCE_READ);
        if(result==null||!result.isObject()||result.toString().getBytes(StandardCharsets.UTF_8).length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("EVIDENCE_GATEWAY_UNAVAILABLE");
        return result;
    }
    private JsonNode fetch(URI uri,long end,BooleanSupplier cancelled){
        var req=HttpRequest.newBuilder(uri).timeout(ResearchRunner.remaining(end,cancelled))
                .header("Accept-Encoding", "identity").GET().build();
        var f=http.sendAsync(req, info -> new BoundedEvidenceBody(supportedResponse(info)));
        try{
            HttpResponse<byte[]> response;
            while(true){ResearchRunner.remaining(end,cancelled);try{response=f.get(50,TimeUnit.MILLISECONDS);break;}catch(TimeoutException poll){}}
            return JSON.readTree(response.body());
        }catch(Exception e){
            var classified=classifyFailure(e);
            if(classified instanceof CancellationException)throw classified;
            // Recheck the original deadline/cancellation, never start a fresh verification budget.
            // The HTTP future may also complete exceptionally just before that deadline.
            ResearchRunner.remaining(end,cancelled);
            throw classified;
        }
        finally{if(!f.isDone())f.cancel(true);}
    }

    private static boolean supportedResponse(HttpResponse.ResponseInfo info) {
        if (info.statusCode() != 200) return false;
        var encodings = info.headers().allValues("Content-Encoding");
        if (!encodings.isEmpty() && (encodings.size() != 1 || !encodings.get(0).equalsIgnoreCase("identity"))) {
            return false;
        }
        var lengths = info.headers().allValues("Content-Length");
        if (lengths.isEmpty()) return true;
        if (lengths.size() != 1 || !lengths.get(0).matches("[0-9]+")) return false;
        try {
            return Long.parseLong(lengths.get(0)) <= MAX_EVIDENCE_BYTES;
        } catch (NumberFormatException invalidLength) {
            return false;
        }
    }

    private static final class BoundedEvidenceBody implements HttpResponse.BodySubscriber<byte[]> {
        private final boolean supported;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final byte[] copyBuffer = new byte[8192];
        private Flow.Subscription subscription;

        private BoundedEvidenceBody(boolean supported) { this.supported = supported; }

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (supported) subscription.request(1);
            else reject();
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_EVIDENCE_BYTES - bytes.size()) {
                    reject();
                    return;
                }
                while (buffer.hasRemaining()) {
                    int length = Math.min(buffer.remaining(), copyBuffer.length);
                    buffer.get(copyBuffer, 0, length);
                    bytes.write(copyBuffer, 0, length);
                }
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }

        @Override public void onComplete() { if (!body.isDone()) body.complete(bytes.toByteArray()); }

        private void reject() {
            body.completeExceptionally(new IllegalStateException("Invalid evidence HTTP response"));
            subscription.cancel();
        }
    }

    static RuntimeException classifyFailure(Throwable failure){
        var causes=new ArrayList<Throwable>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null&&causes.size()<16&&seen.add(cause);cause=cause.getCause())causes.add(cause);
        for(var cause:causes)if(cause instanceof InterruptedException){Thread.currentThread().interrupt();return new CancellationException("RESEARCH_CANCELLED");}
        for(var cause:causes)if(cause instanceof CancellationException)return new CancellationException("RESEARCH_CANCELLED");
        for(var cause:causes)if(cause instanceof HttpTimeoutException||cause instanceof TimeoutException)return new IllegalStateException("RESEARCH_DEADLINE");
        // Unknown transport bodies, URIs and messages cannot forge a public control classification.
        return new IllegalStateException("EVIDENCE_GATEWAY_FAILED");
    }
    private static int line(String raw,int at){int line=1;for(int i=0;i<at;i++)if(raw.charAt(i)=='\n')line++;return line;}
}
