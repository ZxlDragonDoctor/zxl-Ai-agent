package com.zxl.zxlaiagent.persistence;

import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;

/** Read-only candidate gate. No migration identity or business operations are required. */
public final class SchemaVerifier {
 private SchemaVerifier() {}
 public record Result(int exitCode,String message) {}
 @FunctionalInterface interface Check {
  void verify(String module,String url,String user,String password,DatabaseSchemaLifecycle.Mode mode);
 }
 static Result run(String[] modules,Map<String,String> environment,Check check) {
  if(modules==null||modules.length==0)return new Result(2,"SCHEMA_MODULE_INVALID");
  var seen=new java.util.HashSet<String>();
  for(String module:modules)if(module==null||!Set.of("security","knowledge","lesson9").contains(module)||!seen.add(module))return new Result(2,"SCHEMA_MODULE_INVALID");
  if(environment==null)return new Result(2,"SCHEMA_ENV_REQUIRED");
  for(String name:new String[]{"LESSON12_DB_URL","LESSON12_DB_USER","LESSON12_DB_PASSWORD"})if(environment.get(name)==null||environment.get(name).isBlank())return new Result(2,"SCHEMA_ENV_REQUIRED");
  String url=environment.get("LESSON12_DB_URL");
  if(!url.startsWith("jdbc:postgresql:"))return new Result(2,"POSTGRES_REQUIRED");
  try {
   for(String module:modules)check.verify(module,url,environment.get("LESSON12_DB_USER"),environment.get("LESSON12_DB_PASSWORD"),DatabaseSchemaLifecycle.Mode.VALIDATE);
   return new Result(0,"SCHEMA_VERIFIED");
  } catch(RuntimeException failure){return new Result(1,"SCHEMA_NOT_READY");}
 }
 private static void verify(String module,String url,String user,String password,DatabaseSchemaLifecycle.Mode mode) {
  String schema=switch(module){case "security"->"lesson17";case "knowledge"->"lesson12";default->"public";};
  String location=module.equals("lesson9")?"lesson9":schema;
  var configuration=Flyway.configure().dataSource(url,user,password).locations("classpath:db/"+location).table(location+"_schema_history").createSchemas(false);
  if(!module.equals("lesson9"))configuration.schemas(schema).defaultSchema(schema);
  DatabaseSchemaLifecycle.apply(configuration.load(),mode);
 }
 public static void main(String[] modules) {
  // This is an isolated command process. Library JDBC/Flyway diagnostics can contain URLs.
  var logger=org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  if(logger instanceof ch.qos.logback.classic.Logger logback)logback.setLevel(ch.qos.logback.classic.Level.OFF);
  Result result=run(modules,System.getenv(),SchemaVerifier::verify);
  if(result.exitCode()==0)System.out.println(result.message());else System.err.println(result.message());
  if(result.exitCode()!=0)System.exit(result.exitCode());
 }
}
