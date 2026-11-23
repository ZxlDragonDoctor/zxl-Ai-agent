package com.zxl.zxlaiagent.persistence;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaVerifierTest {
 private static Map<String,String> environment(){return Map.of("LESSON12_DB_URL","jdbc:postgresql://127.0.0.1/test","LESSON12_DB_USER","runtime","LESSON12_DB_PASSWORD","synthetic-secret-do-not-print");}
 @Test void duplicateModulesRejectBeforeEnvironmentOrDatabaseAccess(){
  var called=new java.util.concurrent.atomic.AtomicBoolean();
  var result=SchemaVerifier.run(new String[]{"security","security"},null,(m,u,n,p,mode)->called.set(true));
  assertEquals(2,result.exitCode());assertEquals("SCHEMA_MODULE_INVALID",result.message());assertFalse(called.get());
 }
 @Test void invalidModuleRejectsEntireRequestBeforeReadingEnvironmentOrConnecting(){
  var called=new java.util.concurrent.atomic.AtomicBoolean();
  var r=SchemaVerifier.run(new String[]{"security","invalid"},null,(m,u,n,p,mode)->called.set(true));
  assertEquals(2,r.exitCode());assertEquals("SCHEMA_MODULE_INVALID",r.message());assertFalse(called.get());
 }
 @Test void noArgumentsAndMissingEnvironmentNeverConnect(){
  SchemaVerifier.Check check=(m,u,n,p,mode)->fail("Must not connect");
  assertEquals(2,SchemaVerifier.run(new String[0],environment(),check).exitCode());
  assertEquals("SCHEMA_ENV_REQUIRED",SchemaVerifier.run(new String[]{"security"},Map.of(),check).message());
  var env=new HashMap<>(environment());env.put("LESSON12_DB_URL","jdbc:h2:mem:test");
  assertEquals("POSTGRES_REQUIRED",SchemaVerifier.run(new String[]{"knowledge"},env,check).message());
 }
 @Test void allWhitelistedChecksOnlyReceiveValidateMode(){
  var modules=new ArrayList<String>();
  var r=SchemaVerifier.run(new String[]{"security","knowledge","lesson9"},environment(),(m,u,n,p,mode)->{modules.add(m);assertEquals(DatabaseSchemaLifecycle.Mode.VALIDATE,mode);assertEquals(environment().get("LESSON12_DB_URL"),u);});
  assertEquals(0,r.exitCode());assertEquals("SCHEMA_VERIFIED",r.message());assertEquals(List.of("security","knowledge","lesson9"),modules);
 }
 @Test void failedCheckReturnsOnlyFixedCodeAndStopsLaterModules(){
  var modules=new ArrayList<String>();var r=SchemaVerifier.run(new String[]{"security","knowledge"},environment(),(m,u,n,p,mode)->{modules.add(m);throw new IllegalStateException(p+u);});
  assertEquals(1,r.exitCode());assertEquals("SCHEMA_NOT_READY",r.message());assertEquals(List.of("security"),modules);assertFalse(r.toString().contains("synthetic-secret"));
 }
}
