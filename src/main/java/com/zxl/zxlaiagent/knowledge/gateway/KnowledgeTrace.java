package com.zxl.zxlaiagent.knowledge.gateway;

import java.util.UUID;
import java.util.regex.Pattern;

/** Internal diagnostics only. Trace headers never establish identity or authorization. */
public final class KnowledgeTrace {
    public static final String HEADER = "traceparent";
    private static final Pattern FORMAT = Pattern.compile("00-(?!0{32}-)[0-9a-f]{32}-(?!0{16}-)[0-9a-f]{16}-0[01]");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private KnowledgeTrace() {}

    public interface Scope extends AutoCloseable { @Override void close(); }

    public static String fromRun(String runId) {
        if (runId == null) return null;
        try {
            String trace = UUID.fromString(runId).toString().replace("-", "");
            return valid("00-" + trace + "-" + spanId() + "-01");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String valid(String header) {
        return header != null && FORMAT.matcher(header).matches() ? header : null;
    }

    public static String child(String parent) {
        return valid(parent) == null ? null : parent.substring(0, 36) + spanId() + parent.substring(52);
    }

    public static String current() { return CURRENT.get(); }

    public static Scope open(String header) {
        String previous = current();
        set(valid(header));
        return () -> set(previous);
    }

    private static void set(String value) {
        if (value == null) CURRENT.remove();
        else CURRENT.set(value);
    }

    private static String spanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
