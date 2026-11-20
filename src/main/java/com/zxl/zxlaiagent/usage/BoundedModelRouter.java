package com.zxl.zxlaiagent.usage;

import com.zxl.zxlaiagent.security.SecurityFailure;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Deployment-owned primary and optional fallback. Each attempt must acquire its own budget. */
public final class BoundedModelRouter {
    @FunctionalInterface public interface Attempt<T> { T call(String model, int attemptIndex); }
    private final String primary;
    private final String fallback;

    public BoundedModelRouter(String primary, String fallback) {
        this.primary = model(primary);
        this.fallback = fallback == null || fallback.isBlank() ? null : model(fallback);
        if (this.primary.equals(this.fallback)) throw new IllegalArgumentException("Fallback must be distinct");
    }

    public <T> T execute(Attempt<T> attempt, BooleanSupplier cancelled, BooleanSupplier emittedOutput) {
        checkCancelled(cancelled);
        try {
            return attempt.call(primary, 0);
        } catch (RuntimeException failure) {
            if (fallback == null || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                    || emittedOutput.getAsBoolean() || !transientProviderFailure(failure)) throw failure;
            checkCancelled(cancelled);
            // A second failure escapes. Never recurse or retry the same model here.
            return attempt.call(fallback, 1);
        }
    }

    private static boolean transientProviderFailure(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean transientStatus = false;
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            // Reactor attaches diagnostic assembly traces to ordinary HTTP failures.
            // Other suppressed failures (including accounting) conservatively forbid fallback.
            if (java.util.Arrays.stream(cause.getSuppressed()).anyMatch(extra -> !reactor.core.Exceptions.isTraceback(extra))) return false;
            if (cause instanceof SecurityFailure || cause instanceof CancellationException
                    || cause instanceof InterruptedException) return false;
            if (cause instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                if (status != 429 && status != 503) return false;
                transientStatus = true;
            }
        }
        return transientStatus;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Model call cancelled");
    }

    private static String model(String name) {
        if (name == null || name.isBlank() || name.length() > 150 || !name.equals(name.trim())
                || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Configured model required");
        return name;
    }
}
