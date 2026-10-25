package com.zxl.zxlaiagent.skills;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Reviewed application configuration, never a class name, script, URL or user-supplied prompt loader. */
public record SkillDefinition(String skillId,String version,String description,List<String> steps,
        List<ToolReference> tools,String outputSchemaVersion,int budgetMs,String state,String sha256) {
    private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final List<String> EXECUTION_STEPS=List.of("CLARIFY","LOOKUP","VERIFY_ADVISE","DRAFT");
    public record ToolReference(String serverId,String originalName,String exposedName,String schemaVersion,String schemaSha256,boolean readOnly) { }
    public SkillDefinition {
        if(skillId==null||!skillId.matches("[a-z][a-z0-9-]{1,63}")||version==null||!version.matches("v[1-9][0-9]{0,3}")
                ||description==null||description.isBlank()||description.length()>1000||!EXECUTION_STEPS.equals(steps)
                ||tools==null||tools.size()!=1||!approvedTool().equals(tools.get(0))
                ||outputSchemaVersion==null||!Set.of("ticket-draft-v1","ticket-draft-v2").contains(outputSchemaVersion)
                ||budgetMs<100||budgetMs>30000||state==null||!Set.of("ACTIVE","DISABLED").contains(state))
            throw new IllegalArgumentException("SKILL_INVALID_DEFINITION");
        steps=List.copyOf(steps);tools=List.copyOf(tools);
        String actual=digest(documentOf(skillId,version,description,steps,tools,outputSchemaVersion,budgetMs,state));
        if(!actual.equals(sha256))throw new IllegalArgumentException("SKILL_DIGEST_MISMATCH");
    }
    public Map<String,Object> document(){return documentOf(skillId,version,description,steps,tools,outputSchemaVersion,budgetMs,state);}
    private static Map<String,Object> documentOf(String id,String version,String description,List<String> steps,List<ToolReference> tools,String output,int budget,String state){
        var document=new TreeMap<String,Object>();document.put("skillId",id);document.put("version",version);document.put("description",description);
        document.put("steps",steps);document.put("tools",tools);document.put("outputSchemaVersion",output);document.put("budgetMs",budget);document.put("state",state);return Collections.unmodifiableMap(document);
    }
    public static SkillDefinition parse(String input){
        if(input==null||input.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("SKILL_DEFINITION_SIZE");
        try {
            JsonNode root=JSON.readTree(input);
            exactFields(root,Set.of("skillId","version","description","steps","tools","outputSchemaVersion","budgetMs","state"));
            if(!root.path("steps").isArray()||root.path("steps").size()!=4||!root.path("tools").isArray()||root.path("tools").size()!=1
                    ||!root.path("budgetMs").isIntegralNumber()||!root.path("budgetMs").canConvertToInt())throw new IllegalArgumentException("SKILL_INVALID_DEFINITION");
            List<String> steps=new ArrayList<>();for(var step:root.path("steps"))steps.add(text(step));
            var tool=root.path("tools").get(0);exactFields(tool,Set.of("serverId","originalName","exposedName","schemaVersion","schemaSha256","readOnly"));
            if(!tool.path("readOnly").isBoolean())throw new IllegalArgumentException("SKILL_INVALID_TOOL");
            var tools=List.of(new ToolReference(text(tool.path("serverId")),text(tool.path("originalName")),text(tool.path("exposedName")),text(tool.path("schemaVersion")),text(tool.path("schemaSha256")),tool.path("readOnly").asBoolean()));
            String id=text(root.path("skillId")),version=text(root.path("version")),description=text(root.path("description")),output=text(root.path("outputSchemaVersion")),state=text(root.path("state"));int budget=root.path("budgetMs").asInt();
            return new SkillDefinition(id,version,description,steps,tools,output,budget,state,digest(documentOf(id,version,description,steps,tools,output,budget,state)));
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("SKILL_INVALID_DEFINITION");}
    }
    private static String text(JsonNode value){if(!value.isTextual())throw new IllegalArgumentException("SKILL_STRING_REQUIRED");return value.textValue();}
    private static void exactFields(JsonNode node,Set<String> fields){if(node==null||!node.isObject()||node.size()!=fields.size())throw new IllegalArgumentException("SKILL_UNKNOWN_OR_MISSING_FIELD");node.fieldNames().forEachRemaining(key->{if(!fields.contains(key))throw new IllegalArgumentException("SKILL_UNKNOWN_FIELD");});}
    public static ToolReference approvedTool(){return new ToolReference("knowledge",KnowledgeLookupTool.NAME,"knowledge__knowledge_lookup_v1","v1",digest(KnowledgeLookupTool.definition().inputSchema()),true);}
    public static String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ToolResults.json(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SKILL_DIGEST_FAILURE",e);}}
}
