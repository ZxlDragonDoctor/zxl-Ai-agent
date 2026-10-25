package com.zxl.zxlaiagent.skills;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed classroom scope only. A server-created context is not a production login or tenant authority. */
public final class SkillExecutionPolicy {
    public record Context(String subject,String tenant,Set<String> identityTools,Set<String> agentTools){
        public Context{identityTools=Set.copyOf(identityTools);agentTools=Set.copyOf(agentTools);}
    }
    private static final String TOOL="knowledge__knowledge_lookup_v1";
    private final Set<String> reviewedServerTools;
    private final Set<String> revoked=new HashSet<>();
    private final AtomicLong revision=new AtomicLong(1);
    public SkillExecutionPolicy(Set<String> serverTools){reviewedServerTools=Set.copyOf(serverTools);if(!Set.of(TOOL).containsAll(reviewedServerTools))throw new IllegalArgumentException("SKILL_SERVER_POLICY_TOOL");}
    public static Context demo(){return new Context("demo-learner","demo-enterprise",Set.of(TOOL),Set.of(TOOL));}
    public String version(){return "classroom-policy-v1-r"+revision.get();}
    public synchronized void revoke(String tool){if(!reviewedServerTools.contains(tool))throw new IllegalArgumentException("SKILL_UNKNOWN_TOOL");if(revoked.add(tool))revision.incrementAndGet();}
    public synchronized void restore(String tool){if(!reviewedServerTools.contains(tool))throw new IllegalArgumentException("SKILL_UNKNOWN_TOOL");if(revoked.remove(tool))revision.incrementAndGet();}
    public synchronized void authorize(Context context,SkillDefinition definition,String tool,boolean available){
        if(context==null||!"demo-learner".equals(context.subject())||!"demo-enterprise".equals(context.tenant())
                ||!reviewedServerTools.contains(tool)||revoked.contains(tool)||!context.identityTools().contains(tool)||!context.agentTools().contains(tool)
                ||definition.tools().stream().noneMatch(t->t.readOnly()&&t.exposedName().equals(tool)))throw new SecurityException("SKILL_POLICY_DENIED");
        if(!available)throw new IllegalStateException("SKILL_TOOL_UNAVAILABLE");
    }
}
