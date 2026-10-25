package com.zxl.zxlaiagent.conversation.agent;

/** Business selection only. Identity, endpoints and tool permissions never come from this request. */
public record AgentRunOptions(String skillId, String skillVersion) {
    public static final AgentRunOptions NONE = new AgentRunOptions(null, null);
    public AgentRunOptions {
        if (skillId != null && !skillId.matches("[a-z][a-z0-9-]{1,63}"))
            throw new IllegalArgumentException("INVALID_SKILL_SELECTION");
        if (skillVersion != null && !skillVersion.matches("v[1-9][0-9]{0,3}"))
            throw new IllegalArgumentException("INVALID_SKILL_VERSION");
        if (skillVersion != null && skillId == null)
            throw new IllegalArgumentException("SKILL_ID_REQUIRED");
    }
    public boolean isEmpty() { return skillId == null && skillVersion == null; }
}
