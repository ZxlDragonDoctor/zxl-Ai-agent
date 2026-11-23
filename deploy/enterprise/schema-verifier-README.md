# Candidate schema compatibility check

`com.zxl.zxlaiagent.persistence.SchemaVerifier` is a one-shot read-only candidate check. Explicit modules are `security knowledge lesson9`; no module selection is inferred. It reads the candidate process's `LESSON12_DB_URL`, `LESSON12_DB_USER`, `LESSON12_DB_PASSWORD`. Use the restricted runtime identity and candidate image, not the migration identity or an unrelated current JAR.

```sh
docker compose --project-name <explicit-project> --env-file /absolute/private.env -f <actual-compose.json> run --rm --no-deps -T --entrypoint java main -Dloader.main=com.zxl.zxlaiagent.persistence.SchemaVerifier -cp /opt/agent/app.jar org.springframework.boot.loader.launch.PropertiesLauncher security knowledge
```

The check always selects shared `DatabaseSchemaLifecycle.Mode.VALIDATE`, regardless of `AGENT_DB_SCHEMA_MODE`. It does not call migrate, create a schema or touch business data. Invalid modules are rejected before environment access or connection creation. Success prints only `SCHEMA_VERIFIED` and exits0. Bad arguments/config exit2, schema/connection failure exits1 as `SCHEMA_NOT_READY`; library logs are suppressed for this CLI so credential-bearing connection errors and raw stack traces are not printed.

Shared validation checks migration history/checksums and rejects pending/future migrations. It does not prove arbitrary manual column consistency, semantic application/data compatibility, model/tool behavior or safe business rollback. Passing it is one deployment gate; start the candidate and validate authenticated business behavior, sessions, stored reports and budgets separately. A failed check must not run migration automatically or trigger a blind rollback against a newer schema.

Tests inject the database check to verify argument fail-fast behavior, whitelist, explicit VALIDATE mode and sanitized failure. Actual candidate-container checks and A/B switch evidence are recorded by the parent acceptance task.
