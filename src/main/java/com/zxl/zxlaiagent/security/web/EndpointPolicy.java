package com.zxl.zxlaiagent.security.web;

import java.util.Set;
import java.util.regex.Pattern;

/** Reviewed formal-mode entry catalogue. Unknown paths/methods never inherit permissions. */
public final class EndpointPolicy {
    private EndpointPolicy() {}
    public record Requirement(boolean publicAccess, boolean blocked, String action,
                              String resourceType, String resourceId, boolean csrf) {}
    private static final Requirement BLOCKED = new Requirement(false, true, null, null, null, false);
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern CONVERSATION = Pattern.compile("/conversations/(" + UUID + ")");
    private static final Pattern RUN = Pattern.compile("/ai/multiagent/runs/(" + UUID + ")(/cancel)?");
    private static final Pattern DOCUMENT = Pattern.compile("/ai/learning/knowledge/documents/[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    public static String servletPath(String contextPath, String requestUri) {
        if (contextPath == null || requestUri == null || !requestUri.startsWith(contextPath)) return "";
        String path = requestUri.substring(contextPath.length());
        return path.startsWith("/") ? path : "";
    }
    private static Requirement allow(String method, String action) {
        return new Requirement(false, false, action, null, null, !SAFE.contains(method));
    }
    private static Requirement own(String method, String action, String type, String id) {
        return new Requirement(false, false, action, type, id, !SAFE.contains(method));
    }
    public static Requirement classify(String method, String path) {
        if (method == null || path == null || path.length() > 512 || !path.startsWith("/")
                || path.contains("//") || path.contains("%") || path.contains(";")
                || path.contains("\\") || path.contains("/../") || path.contains("/./")
                || path.endsWith("/..") || path.endsWith("/.")) return BLOCKED;
        if (("GET".equals(method) && Set.of("/auth/status", "/health/live").contains(path))
                || ("POST".equals(method) && "/auth/login".equals(path)))
            return new Requirement(true, false, null, null, null, "POST".equals(method));
        if ("POST".equals(method) && "/auth/logout".equals(path)) return allow(method, null);
        if ("GET".equals(method) && "/agent-types".equals(path)) return allow(method, "agent.execute");
        if (Set.of("GET", "POST").contains(method) && "/conversations".equals(path))
            return allow(method, "agent.execute");
        var conversation = CONVERSATION.matcher(path);
        if (conversation.matches() && Set.of("GET", "DELETE").contains(method))
            return own(method, "GET".equals(method) ? "conversation.readOwn" : "conversation.writeOwn", "conversation", conversation.group(1));
        if ("POST".equals(method) && Set.of("/ai/agent/chat", "/ai/manus/chat", "/ai/learning/context/preview").contains(path))
            return allow(method, "agent.execute"); // Body resource ownership is checked by the scoped service.
        if ("GET".equals(method) && ("/ai/learning/skills".equals(path)
                || path.matches("/ai/learning/skills/[A-Za-z0-9_-]{1,80}/[A-Za-z0-9._-]{1,40}")))
            return allow(method, "skill.execute");
        if ("GET".equals(method) && "/ai/learning/mcp/status".equals(path)) return allow(method, "knowledge.read");
        if ("POST".equals(method) && "/ai/learning/mcp/lookup".equals(path)) return allow(method, "knowledge.read");
        if ("POST".equals(method) && "/ai/learning/mcp/refresh".equals(path)) return allow(method, "knowledge.manage");
        if ("POST".equals(method) && Set.of("/ai/multiagent/orchestrate", "/ai/multiagent/runs", "/ai/multiagent/orchestrate/stream").contains(path))
            return allow(method, "agent.execute");
        var run = RUN.matcher(path);
        if (run.matches() && run.group(2) == null && "GET".equals(method))
            return own(method, "run.readOwn", "run", run.group(1));
        if (run.matches() && run.group(2) != null && "POST".equals(method))
            return own(method, "run.writeOwn", "run", run.group(1));
        if ("GET".equals(method) && "/ai/multiagent/status".equals(path)) return allow(method, "agent.execute");
        String prefix = "/ai/learning/knowledge";
        if (path.startsWith(prefix + "/")) {
            String suffix = path.substring(prefix.length());
            if ("GET".equals(method) && (Set.of("/status", "/documents", "/search", "/memories", "/memories/recall").contains(suffix)
                    || DOCUMENT.matcher(path).matches())) return allow(method, "knowledge.read");
            if (("POST".equals(method) && "/documents".equals(suffix))
                    || ("DELETE".equals(method) && DOCUMENT.matcher(path).matches())) return allow(method, "knowledge.manage");
            if ("POST".equals(method) && Set.of("/retrieval", "/answers", "/memories/proposals").contains(suffix)) return allow(method, "knowledge.read");
            if (suffix.matches("/memories/[A-Za-z0-9_-]{1,120}(/confirm|/reject)?")
                    && (("POST".equals(method) && (suffix.endsWith("/confirm") || suffix.endsWith("/reject")))
                    || (Set.of("PATCH", "DELETE").contains(method) && !suffix.endsWith("/confirm") && !suffix.endsWith("/reject"))))
                return allow(method, "knowledge.read"); // Worker enforces subject ownership, not a caller profile.
        }
        return BLOCKED;
    }
}
