package com.zxl.zxlaiagent.knowledge.gateway;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.knowledge.scoped.WorkerRequestContract;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.ActorBoundary;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/** Fixed internal destination, request-local actor, one ticket and one bounded HTTP attempt. */
public final class ScopedKnowledgeClient {
    public static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    public record Response(int status, byte[] body) {
        public Response { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    private final URI base;
    private final String serviceToken;
    private final ServiceCallAuthorizer tickets;
    private final AuthorizationService authorization;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> IDENTITY_FIELDS = Set.of("tenant", "tenantId", "user", "userId", "subject", "subjectId", "actor", "actorRef", "profile", "authenticationVersion", "credential", "token", "ticket", "serviceTicket");

    public ScopedKnowledgeClient(URI base, String serviceToken, ServiceCallAuthorizer tickets, AuthorizationService authorization) {
        if (base == null || !"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost())
                || base.getPort() < 1 || base.getPort() > 65535 || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getFragment() != null
                || !(base.getRawPath().isEmpty() || "/".equals(base.getRawPath()))) {
            throw new IllegalArgumentException("Explicit fixed http://127.0.0.1:port worker origin required");
        }
        if (serviceToken == null || !serviceToken.matches("[A-Za-z0-9_-]{43,256}") || serviceToken.chars().distinct().count() < 12) {
            throw new IllegalArgumentException("Explicit high-entropy internal service credential required");
        }
        this.base = URI.create("http://127.0.0.1:" + base.getPort());
        this.serviceToken = serviceToken;
        this.tickets = Objects.requireNonNull(tickets);
        this.authorization = Objects.requireNonNull(authorization);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {}
                }).build();
    }

    public Response exchange(ActorRef actor, String method, String path, String rawQuery, byte[] originalBody, Duration budget) {
        return exchange(actor, method, path, rawQuery, originalBody, budget, () -> false, List.of(), null);
    }

    /** Additional permissions and runId are trusted Java execution context, never parsed from browser/model JSON. */
    public Response exchange(ActorRef actor, String method, String path, String rawQuery, byte[] originalBody,
            Duration budget, BooleanSupplier cancelled, List<AuthorizationService.Permission> additionalPermissions, String runId) {
        try {
            return exchangeInternal(actor, method, path, rawQuery, originalBody, budget, cancelled, additionalPermissions, runId);
        } catch (SecurityFailure denied) {
            // Do not distinguish a foreign resource from a missing one at the gateway boundary.
            if ("RESOURCE_UNAVAILABLE".equals(denied.code())) throw new SecurityFailure("NOT_FOUND", 404);
            throw denied;
        }
    }

    private Response exchangeInternal(ActorRef actor, String method, String path, String rawQuery, byte[] originalBody,
            Duration budget, BooleanSupplier cancelled, List<AuthorizationService.Permission> additionalPermissions, String runId) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(cancelled);
        if (additionalPermissions == null || additionalPermissions.size() > 7) throw invalid();
        if (budget == null || budget.compareTo(Duration.ofMillis(100)) < 0 || budget.compareTo(Duration.ofSeconds(30)) > 0) throw invalid();
        long end = System.nanoTime() + budget.toNanos();
        if (originalBody != null && originalBody.length > MAX_REQUEST_BYTES) throw invalid();
        byte[] body = originalBody == null ? new byte[0] : originalBody.clone();
        if (body.length > MAX_REQUEST_BYTES || path == null || path.length() > 512 || rawQuery != null && rawQuery.length() > 8192) throw invalid();
        final String target, action;
        try {
            target = WorkerRequestContract.canonicalTarget(path, rawQuery);
            action = WorkerRequestContract.action(method, path);
            validateBody(method, body);
        } catch (IllegalArgumentException failure) { throw invalid(); }
        var permissions = new LinkedHashSet<AuthorizationService.Permission>();
        permissions.add(new AuthorizationService.Permission(action, null));
        permissions.addAll(additionalPermissions);
        var boundary = new ActorBoundary(authorization, actor, List.copyOf(permissions), runId);
        remaining(end, cancelled);
        // Ticket issuance owns its own PG transaction. Never nest it inside the transport gate.
        String ticket = tickets.issue(actor, WorkerRequestContract.AUDIENCE, action, null,
                ServiceCallAuthorizer.requestHash(method, target, body));
        var request = HttpRequest.newBuilder(base.resolve(target)).timeout(remaining(end, cancelled))
                .header("Authorization", "Bearer " + serviceToken)
                .header(WorkerRequestContract.TICKET_HEADER, ticket)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build();
        CompletableFuture<HttpResponse<byte[]>> pending = boundary.start(Phase.TOOL_DISPATCH,
                () -> { remaining(end, cancelled); return http.sendAsync(request, ignored -> new LimitedBodySubscriber(MAX_RESPONSE_BYTES)); });
        try {
            HttpResponse<byte[]> response;
            while (true) {
                long slice = Math.min(TimeUnit.MILLISECONDS.toNanos(50), remaining(end, cancelled).toNanos());
                try { response = pending.get(slice, TimeUnit.NANOSECONDS); break; }
                catch (TimeoutException sliceExpired) { remaining(end, cancelled); }
            }
            remaining(end, cancelled);
            if (response.statusCode() >= 300 && response.statusCode() < 400) throw new SecurityFailure("KNOWLEDGE_WORKER_REDIRECT_REJECTED", 502);
            HttpResponse<byte[]> completed = response;
            return boundary.start(Phase.RESULT_PUBLISH, () -> { remaining(end, cancelled); return new Response(completed.statusCode(), completed.body()); });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SecurityFailure("KNOWLEDGE_CANCELLED", 503);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause != null) {
                if (cause instanceof ResponseTooLarge) throw new SecurityFailure("KNOWLEDGE_RESPONSE_TOO_LARGE", 502);
                if (cause instanceof HttpTimeoutException) throw new SecurityFailure("KNOWLEDGE_WORKER_TIMEOUT", 504);
                cause = cause.getCause();
            }
            throw new SecurityFailure("KNOWLEDGE_WORKER_UNAVAILABLE", 503);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private void validateBody(String method, byte[] body) {
        if (Set.of("GET", "DELETE").contains(method)) { if (body.length != 0) throw invalid(); return; }
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) throw invalid();
            rejectIdentity(node);
        } catch (java.io.IOException malformed) { throw invalid(); }
    }
    private void rejectIdentity(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (IDENTITY_FIELDS.contains(field.getKey())) throw invalid();
                rejectIdentity(field.getValue());
            });
        } else if (node.isArray()) node.forEach(this::rejectIdentity);
    }
    private static Duration remaining(long end, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new SecurityFailure("KNOWLEDGE_CANCELLED", 503);
        long nanos = end - System.nanoTime();
        if (nanos <= 0) throw new SecurityFailure("KNOWLEDGE_WORKER_TIMEOUT", 504);
        return Duration.ofNanos(nanos);
    }
    private static SecurityFailure invalid() { return new SecurityFailure("INVALID_KNOWLEDGE_REQUEST", 400); }
    private static final class ResponseTooLarge extends RuntimeException {}
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new ResponseTooLarge()); return;
                }
                byte[] next = new byte[buffer.remaining()]; buffer.get(next); bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
