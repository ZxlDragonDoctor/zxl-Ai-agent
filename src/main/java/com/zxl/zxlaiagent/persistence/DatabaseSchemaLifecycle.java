package com.zxl.zxlaiagent.persistence;

import org.flywaydb.core.Flyway;

/** Schema ownership is deployment policy, never a request/model argument. */
public final class DatabaseSchemaLifecycle {
 private DatabaseSchemaLifecycle() {}
 public enum Mode { MIGRATE, VALIDATE;
  public static Mode environment(){
   String mode=System.getenv().getOrDefault("AGENT_DB_SCHEMA_MODE","migrate");
   return switch(mode){case "migrate"->MIGRATE;case "validate"->VALIDATE;default->throw new IllegalArgumentException("INVALID_DATABASE_SCHEMA_MODE");};
  }
 }
 public static void apply(Flyway configured,Mode mode){
  if(mode==null)throw new IllegalArgumentException("DATABASE_SCHEMA_MODE_REQUIRED");
  if(mode==Mode.MIGRATE){configured.migrate();return;}
  // Never create schemas or write migration history with a runtime identity.
  var readOnly=Flyway.configure().configuration(configured.getConfiguration()).createSchemas(false).load();
  try {
   readOnly.validate();
   var info=readOnly.info();
   boolean future=java.util.Arrays.stream(info.all()).anyMatch(m->m.getState()==org.flywaydb.core.api.MigrationState.FUTURE_SUCCESS || m.getState()==org.flywaydb.core.api.MigrationState.FUTURE_FAILED);
   if(info.pending().length!=0 || future)throw new IllegalStateException("DATABASE_MIGRATION_VERSION_MISMATCH");
  }catch(RuntimeException failure){throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY",failure);}
 }
}
