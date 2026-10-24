package com.zxl.zxlaiagent.tools.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Explicit JSON Schema subset. Unsupported constraints fail closed rather than being ignored. */
final class McpInputValidator {
    private static final ObjectMapper MAPPER=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private McpInputValidator() { }
    static JsonNode parse(String input) throws Exception { return MAPPER.readTree(input); }
    static Map<String,Object> validate(String input,McpSchema.Tool tool,int maxBytes) {
        try {
            if(input==null||input.getBytes(StandardCharsets.UTF_8).length>maxBytes) throw new IllegalArgumentException("bytes");
            JsonNode value=parse(input);
            if(value==null||!value.isObject()) throw new IllegalArgumentException("object required");
            validate(value,MAPPER.valueToTree(tool.inputSchema()),0);
            return MAPPER.convertValue(value,new TypeReference<>() {});
        } catch(Exception e) { throw new IllegalArgumentException("MCP 参数或 schema 不合法",e); }
    }
    private static void validate(JsonNode value,JsonNode schema,int depth) {
        if(depth>12||!schema.isObject()||!schema.path("type").isTextual()) throw new IllegalArgumentException("schema");
        Set<String> supported=Set.of("type","description","title","default","enum","properties","required",
                "additionalProperties","minLength","maxLength","minimum","maximum","items","minItems","maxItems");
        schema.fieldNames().forEachRemaining(key->{if(!supported.contains(key))throw new IllegalArgumentException("unsupported schema");});
        String type=schema.path("type").asText();
        switch(type) {
            case "object" -> {
                if(!value.isObject()||value.size()>64)throw new IllegalArgumentException("object");
                JsonNode properties=schema.path("properties");
                if(!properties.isMissingNode()&&!properties.isObject())throw new IllegalArgumentException("properties");
                if(schema.has("additionalProperties")&&!schema.path("additionalProperties").isBoolean())
                    throw new IllegalArgumentException("additionalProperties");
                if(schema.has("required")) {
                    if(!schema.path("required").isArray())throw new IllegalArgumentException("required");
                    for(JsonNode required:schema.path("required"))
                        if(!required.isTextual()||!value.has(required.asText()))throw new IllegalArgumentException("required");
                }
                var fields=value.fields();
                while(fields.hasNext()) {
                    var field=fields.next();
                    // Local closed-world policy also applies when remote schema omits additionalProperties.
                    if(!properties.has(field.getKey()))throw new IllegalArgumentException("unknown field");
                    validate(field.getValue(),properties.path(field.getKey()),depth+1);
                }
            }
            case "string" -> {
                if(!value.isTextual())throw new IllegalArgumentException("string");
                String text=value.asText();
                int length=text.codePointCount(0,text.length());
                if(length<schema.path("minLength").asInt(0)||length>schema.path("maxLength").asInt(16384)
                        ||schema.path("minLength").asInt(0)>0&&text.isBlank())throw new IllegalArgumentException("length");
            }
            case "integer","number" -> {
                if(!value.isNumber()||type.equals("integer")&&!value.isIntegralNumber())throw new IllegalArgumentException("number");
                if(schema.has("minimum")&&value.decimalValue().compareTo(schema.path("minimum").decimalValue())<0
                        ||schema.has("maximum")&&value.decimalValue().compareTo(schema.path("maximum").decimalValue())>0)
                    throw new IllegalArgumentException("range");
            }
            case "boolean" -> { if(!value.isBoolean())throw new IllegalArgumentException("boolean"); }
            case "array" -> {
                if(!value.isArray()||value.size()<schema.path("minItems").asInt(0)
                        ||value.size()>Math.min(64,schema.path("maxItems").asInt(64)))throw new IllegalArgumentException("array");
                for(JsonNode item:value)validate(item,schema.path("items"),depth+1);
            }
            default -> throw new IllegalArgumentException("unsupported type");
        }
        if(schema.has("enum")) {
            if(!schema.path("enum").isArray())throw new IllegalArgumentException("enum");
            boolean present=false;
            for(JsonNode allowed:schema.path("enum"))if(allowed.equals(value))present=true;
            if(!present)throw new IllegalArgumentException("enum value");
        }
    }
}
