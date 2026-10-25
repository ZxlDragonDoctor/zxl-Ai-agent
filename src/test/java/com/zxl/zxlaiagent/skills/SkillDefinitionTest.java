package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.tools.support.ToolResults;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillDefinitionTest {
    @Test void reviewedVersionsHaveDistinctImmutableDigestsAndOutputContracts() {
        var definitions=SkillCatalogue.builtIns();
        assertEquals(2,definitions.size());
        assertNotEquals(definitions.get(0).sha256(),definitions.get(1).sha256());
        assertEquals("ticket-draft-v1",definitions.get(0).outputSchemaVersion());
        assertEquals("ticket-draft-v2",definitions.get(1).outputSchemaVersion());
        assertThrows(UnsupportedOperationException.class,()->definitions.get(0).steps().clear());
        assertEquals(definitions.get(0),SkillDefinition.parse(ToolResults.json(definitions.get(0).document())));
    }
    @Test void unknownDuplicateAndOversizedDefinitionsAreRejected() {
        var source=new LinkedHashMap<>(SkillCatalogue.builtIns().get(0).document());
        source.put("className","java.lang.Runtime");
        assertThrows(IllegalArgumentException.class,()->SkillDefinition.parse(ToolResults.json(source)));
        assertThrows(IllegalArgumentException.class,()->SkillDefinition.parse("{\"skillId\":\"a\",\"skillId\":\"b\"}"));
        assertThrows(IllegalArgumentException.class,()->SkillDefinition.parse(ToolResults.json(SkillCatalogue.builtIns().get(0).document())+" {}"));
        assertThrows(IllegalArgumentException.class,()->SkillDefinition.parse(" ".repeat(17000)));
    }
    @Test void duplicateUnknownStepsAndForeignToolsAreRejected() {
        var original=SkillCatalogue.builtIns().get(0).document();
        for(Object bad:List.of(List.of("CLARIFY","LOOKUP","LOOKUP","DRAFT"),List.of("CLARIFY","EXECUTE_SHELL","VERIFY_ADVISE","DRAFT"))) {
            var source=new LinkedHashMap<>(original);source.put("steps",bad);
            assertThrows(IllegalArgumentException.class,()->SkillDefinition.parse(ToolResults.json(source)));
        }
        var source=new LinkedHashMap<>(original);
        source.put("tools",List.of(Map.of("serverId","admin","originalName","shell","exposedName","admin__shell","schemaVersion","v1","schemaSha256","a".repeat(64),"readOnly",false)));
        assertThrows(IllegalArgumentException.class,()->SkillDefinition.parse(ToolResults.json(source)));
        var writeTool=new LinkedHashMap<>(Map.of("serverId","knowledge","originalName","knowledge_lookup_v1","exposedName","knowledge__knowledge_lookup_v1","schemaVersion","v1","schemaSha256",SkillDefinition.approvedTool().schemaSha256(),"readOnly",false));
        source.put("tools",List.of(writeTool));
        assertThrows(IllegalArgumentException.class,()->SkillDefinition.parse(ToolResults.json(source)));
    }
    @Test void directConstructionCannotBypassNullContractValidation() {
        var d=SkillCatalogue.builtIns().get(0);
        assertThrows(IllegalArgumentException.class,()->new SkillDefinition(d.skillId(),d.version(),d.description(),d.steps(),d.tools(),null,d.budgetMs(),d.state(),d.sha256()));
    }
    @Test void conflictAndUnavailableVersionNeverReplaceReviewedDefault() {
        var registry=new SkillRegistry(SkillCatalogue.builtIns());
        var v1=registry.select("it-ticket-analysis",null);
        var changed=new LinkedHashMap<>(v1.document());changed.put("description","same version changed");
        assertThrows(IllegalArgumentException.class,()->registry.register(SkillDefinition.parse(ToolResults.json(changed))));
        assertEquals(v1,registry.select("it-ticket-analysis",null));
        assertThrows(IllegalArgumentException.class,()->registry.select("it-ticket-analysis","v99"));
        registry.setEnabled("it-ticket-analysis","v2",false);
        assertThrows(IllegalStateException.class,()->registry.setDefault("it-ticket-analysis","v2"));
        assertThrows(IllegalStateException.class,()->registry.select("it-ticket-analysis","v2"));
    }
    @Test void defaultRollbackDoesNotMutatePreviouslySelectedVersion() {
        var registry=new SkillRegistry(SkillCatalogue.builtIns());
        var a=registry.select("it-ticket-analysis",null);
        registry.setDefault("it-ticket-analysis","v2");
        var b=registry.select("it-ticket-analysis",null);
        registry.setDefault("it-ticket-analysis","v1");
        assertEquals("v1",a.version());assertEquals("v2",b.version());
        assertEquals(a,registry.select("it-ticket-analysis",null));
        assertEquals(2,registry.list().size());
    }
    @Test void disablingDefaultStillLeavesCatalogueAndPointerReadable() {
        var registry=new SkillRegistry(SkillCatalogue.builtIns());
        registry.setEnabled("it-ticket-analysis","v1",false);
        assertEquals("v1",registry.defaultVersion("it-ticket-analysis"));
        assertFalse(registry.view("it-ticket-analysis","v1").enabled());
        assertEquals(2,registry.list().size());
        assertThrows(IllegalStateException.class,()->registry.select("it-ticket-analysis",null));
    }
}
