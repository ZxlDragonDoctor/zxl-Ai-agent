package com.zxl.zxlaiagent.persistence;

import org.flywaydb.core.Flyway;

/** One-shot deployment command. Supply migration credentials separately from runtime services. */
public final class SchemaMigrator {
 private SchemaMigrator() {}
 public static void main(String[] modules){
  if(modules.length==0)throw new IllegalArgumentException("EXPLICIT_SCHEMA_MODULE_REQUIRED");
  for(String module:modules)if(!java.util.Set.of("security","knowledge","lesson9").contains(module))throw new IllegalArgumentException("INVALID_SCHEMA_MODULE");
  String url=required("LESSON12_DB_URL"),user=required("LESSON12_DB_USER"),password=required("LESSON12_DB_PASSWORD");
  if(!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("POSTGRES_REQUIRED");
  for(String module:modules){
   String schema=switch(module){case "security"->"lesson17";case "knowledge"->"lesson12";default->"public";};
   String location=module.equals("lesson9")?"lesson9":schema;
   var configuration=Flyway.configure().dataSource(url,user,password).locations("classpath:db/"+location).table(location+"_schema_history");
   if(!module.equals("lesson9"))configuration.schemas(schema).defaultSchema(schema);
   DatabaseSchemaLifecycle.apply(configuration.load(),DatabaseSchemaLifecycle.Mode.MIGRATE);
  }
 }
 private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("MISSING_"+name);return value;}
}
