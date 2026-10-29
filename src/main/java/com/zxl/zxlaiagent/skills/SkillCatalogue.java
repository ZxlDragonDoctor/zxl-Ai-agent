package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.tools.support.ToolResults;
import java.util.*;

/** Source-controlled publication. No runtime loading from user paths or downloaded Markdown. */
public final class SkillCatalogue {
    private SkillCatalogue() { }
    public static List<SkillDefinition> builtIns(){return List.of(definition("v1","ticket-draft-v1"),definition("v2","ticket-draft-v2"));}
    public static List<SkillDefinition> formalBuiltIns(){return List.of(formalDefinition("v3","ticket-draft-v1"),formalDefinition("v4","ticket-draft-v2"));}
    private static SkillDefinition formalDefinition(String version,String output){
        return SkillDefinition.parse(ToolResults.json(Map.of("skillId","it-ticket-analysis","version",version,
                "description","当前认证身份的企业IT分析、授权知识查询和待确认草稿 / Authenticated IT analysis, authorized evidence and an uncreated ticket draft",
                "steps",SkillDefinition.EXECUTION_STEPS,"tools",List.of(SkillDefinition.formalTool()),"outputSchemaVersion",output,"budgetMs",30000,"state","ACTIVE")));
    }
    private static SkillDefinition definition(String version,String output){
        return SkillDefinition.parse(ToolResults.json(Map.of("skillId","it-ticket-analysis","version",version,
                "description","企业IT问题澄清、当前知识查询、引用建议与待确认草稿 / IT issue clarification, current evidence and an uncreated ticket draft",
                "steps",SkillDefinition.EXECUTION_STEPS,"tools",List.of(SkillDefinition.approvedTool()),"outputSchemaVersion",output,"budgetMs",30000,"state","ACTIVE")));
    }
}
