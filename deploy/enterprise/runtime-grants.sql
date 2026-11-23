BEGIN;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname IN ('agent_migrator','agent_runtime') AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls OR rolinherit OR rolreplication)) THEN RAISE EXCEPTION 'ROLE_POLICY_MISMATCH'; END IF;
 IF EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname IN ('agent_migrator','agent_runtime')) THEN RAISE EXCEPTION 'ROLE_MEMBERSHIP_FORBIDDEN'; END IF;
 IF EXISTS(SELECT 1 FROM pg_database d JOIN pg_roles r ON r.oid=d.datdba WHERE d.datname=current_database() AND r.rolname='agent_runtime')
 OR EXISTS(SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid=n.nspowner WHERE r.rolname='agent_runtime' AND n.nspname !~ '^pg_' AND n.nspname<>'information_schema')
 OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.oid=c.relowner WHERE r.rolname='agent_runtime' AND n.nspname !~ '^pg_' AND n.nspname<>'information_schema')
 OR EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_roles r ON r.oid=p.proowner WHERE r.rolname='agent_runtime' AND n.nspname !~ '^pg_' AND n.nspname<>'information_schema')
 OR EXISTS(SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace JOIN pg_roles r ON r.oid=t.typowner WHERE r.rolname='agent_runtime' AND n.nspname !~ '^pg_' AND n.nspname<>'information_schema') THEN RAISE EXCEPTION 'RUNTIME_OWNERSHIP_FORBIDDEN'; END IF;
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE agent FROM PUBLIC;
GRANT USAGE ON SCHEMA lesson17,lesson12 TO agent_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA lesson17,lesson12 TO agent_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA lesson17,lesson12 TO agent_runtime;
REVOKE ALL ON lesson17.lesson17_schema_history,lesson12.lesson12_schema_history FROM agent_runtime;
GRANT SELECT ON lesson17.lesson17_schema_history,lesson12.lesson12_schema_history TO agent_runtime;
REVOKE CREATE ON SCHEMA lesson12,lesson17,public FROM PUBLIC,agent_runtime;
REVOKE CREATE,TEMPORARY ON DATABASE agent FROM PUBLIC,agent_runtime;
DO $$ BEGIN
 IF has_schema_privilege('agent_runtime','lesson12','CREATE') OR has_schema_privilege('agent_runtime','lesson17','CREATE') OR has_schema_privilege('agent_runtime','public','CREATE') OR has_database_privilege('agent_runtime',current_database(),'CREATE') OR has_database_privilege('agent_runtime',current_database(),'TEMP') THEN RAISE EXCEPTION 'RUNTIME_DDL_PRIVILEGE_REMAINS'; END IF;
END $$;
COMMIT;
