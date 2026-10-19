package com.zxl.zxlaiagent.multiagent.planning;

import java.util.Locale;
import java.util.Set;

public final class PlanCapabilities {
    public static final Set<String> SUPPORTED = Set.of("research", "code", "supervisor");
    private PlanCapabilities() {}

    public static String normalize(String value, String path) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        normalized = switch (normalized) { case "search" -> "research"; case "review" -> "supervisor"; default -> normalized; };
        if (!SUPPORTED.contains(normalized)) {
            throw new PlanValidationException("UNSUPPORTED_CAPABILITY", path, "当前计划仅支持 research、code、supervisor 能力");
        }
        return normalized;
    }
}
