# PostgreSQL logical backup and isolated restore

Only use a trusted archive: restoring SQL can execute code with the restore operator's privileges. Dumps contain application data, password hashes and sessions. Store dumps/manifests under ignored target, restrict access and do not commit or upload them. This utility does not encrypt files or replace an off-host backup policy.

```sh
node deploy/enterprise/backup.mjs backup --source lesson24-runtime-pg-1 --user bootstrap_admin --database agent --output target/runtime-backup.dump
node deploy/enterprise/backup.mjs restore --archive target/runtime-backup.dump --target lesson24-recovery-pg-1 --user recovery_admin --database agent_recovered
```

All identities are explicit. Paths are limited to new target/*.dump files, target links rejected. Backup streams `pg_dump -Fc` binary stdout directly to an exclusive file descriptor, never PowerShell text redirection. It records canonical source Docker ID and SHA256. Failed backup files and failed manifests remain for diagnosis and cannot be restored. Never silently retry or overwrite them.

Restore verifies successful manifest, bytes/digest and PGDMP signature, pins the target Docker ID and rejects the source container even via a name/ID alias. It checks the target database does not exist, then creates a new database. It never uses --clean, --create, --no-owner or dropdb. `pg_restore --single-transaction --exit-on-error` preserves archive ownership and ACL. On failure the new database remains, together with a failed restore sidecar. No automatic deletion, retry or claim of recovery success.

Prepare the target PostgreSQL with the same required role names (agent_migrator and agent_runtime) but separately selected new passwords. Role/global objects and passwords are not in the database dump. Do **not** pre-create lesson12/lesson17 schemas: restore recreates them and their owners. Install a compatible pgvector image/extension files; trusted administrative restore can create the extension itself. Confirm version compatibility separately.

After restore, verify schema history/owner/role privileges and runtime validate startup. A newly created database may regain default PUBLIC CREATE/TEMP or CONNECT ACL independently of the database contents; explicitly revoke CREATE/TEMP on the restored database from PUBLIC and agent_runtime, then recheck runtime privileges. Restored auth sessions must be revoked before opening access (using the actual session table and application contract); do not treat a copied login session as proof of fresh authentication. Verify run reports, budgets, knowledge data and audit records independently. Only then launch applications with the new runtime credentials and target database name.

Each Docker child command is bounded to ten minutes. A client-side interruption does not prove a remote process stopped; inspect target state before taking further action. Raw PostgreSQL stderr is suppressed because errors can include data. The sidecars record fixed error codes, not stderr, secrets or table data. A matching digest proves byte consistency, not archive trust or business correctness. Source IDs remain in manifests so recovery does not require the source to remain alive.

Tests: `node --test deploy/enterprise/backup.test.mjs` — 8 injected-runner/file fixture tests cover binary preservation, overwrite/path rejection, failed dump, canonical identity aliases, existing target DB rejection, restore options, digest mismatch and failed restore retention. They do not run Docker or prove real backup/restore; parent acceptance records those separately.

## Standalone applications after recovery

`compose-recovery.json` is a standalone file, not an override of `compose-runtime.json`. It has only PG/main/worker/mcp/frontend: no bootstrap, migration or grant jobs. Main waits only for PG health; all Java roles use the explicitly required `RECOVERY_DB_NAME` with `validate` and runtime credentials. It defaults to frontend loopback port5217. Do not run the ordinary runtime topology against a restored database: its initial jobs target `agent`, not your explicitly recovered database.

Prepare role names and new passwords first, without lesson12/17 schemas. Restore trusted owner/ACL metadata into a new database, revoke the restored sessions and database CREATE/TEMP permissions, and verify versions/permissions before opening the proxy. Confirm the Compose project and PG named volume are the existing recovery target's; never let a changed project or volume silently start a different empty PG. Set RECOVERY_DB_NAME=agent_recovered (or the actual new name), explicit image identities and new runtime password. No database port is published by default; any loopback operator port must be a separate acceptance override.

```sh
docker compose --project-name lesson24-recovery --env-file /absolute/private.env -f deploy/enterprise/compose-recovery.json config --quiet
docker compose --project-name lesson24-recovery --env-file /absolute/private.env -f deploy/enterprise/compose-recovery.json up -d --no-build
```

The ordered restart helper can use this file with all original overrides and the matching origin. Recovery configuration tests cover the absent initialization jobs, explicit database name, credential split and namespace/port constraints; actual restore and application acceptance remain separate evidence.
