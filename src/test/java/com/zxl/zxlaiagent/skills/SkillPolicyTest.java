package com.zxl.zxlaiagent.skills;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillPolicyTest {
    static final String TOOL="knowledge__knowledge_lookup_v1";
    @Test void allFourAllowListsAndAvailabilityAreRequired() {
        var definition=SkillCatalogue.builtIns().get(0);
        var policy=new SkillExecutionPolicy(Set.of(TOOL));
        assertDoesNotThrow(()->policy.authorize(SkillExecutionPolicy.demo(),definition,TOOL,true));
        assertThrows(SecurityException.class,()->policy.authorize(SkillExecutionPolicy.demo(),definition,"admin__shell",true));
        assertThrows(SecurityException.class,()->policy.authorize(new SkillExecutionPolicy.Context("demo-learner","demo-enterprise",Set.of(),Set.of(TOOL)),definition,TOOL,true));
        assertThrows(SecurityException.class,()->policy.authorize(new SkillExecutionPolicy.Context("demo-learner","demo-enterprise",Set.of(TOOL),Set.of()),definition,TOOL,true));
        assertThrows(SecurityException.class,()->policy.authorize(new SkillExecutionPolicy.Context("demo-learner","foreign-tenant",Set.of(TOOL),Set.of(TOOL)),definition,TOOL,true));
        assertThrows(IllegalStateException.class,()->policy.authorize(SkillExecutionPolicy.demo(),definition,TOOL,false));
        policy.revoke(TOOL);
        assertThrows(SecurityException.class,()->policy.authorize(SkillExecutionPolicy.demo(),definition,TOOL,true));
        policy.restore(TOOL);
        assertDoesNotThrow(()->policy.authorize(SkillExecutionPolicy.demo(),definition,TOOL,true));
    }
}
