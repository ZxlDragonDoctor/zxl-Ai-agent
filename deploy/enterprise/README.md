# Enterprise container deployment

This topology stages an already built fat JAR and frontend dist; it does not rebuild source or prove their origin. Supply the expected JAR SHA256 from the clean build record. New output directories only:

```
node deploy/enterprise/stage-artifacts.mjs /absolute/app.jar /absolute/dist target/enterprise-stage <jar-sha256>
node deploy/enterprise/stage-artifacts.mjs --check-images
```

`--check-images` requires JAVA_RUNTIME_IMAGE, NGINX_IMAGE and POSTGRES_IMAGE to contain sha256 digests. Compose requires explicit image strings too; run the image check before build (Compose itself does not enforce the digest syntax). Registry digest selection must be recorded from actual pulls, never guessed. Local result image tags are separate from immutable base image digests; record resulting image IDs.

```
docker compose --project-name lesson24-enterprise --env-file /absolute/private.env -f deploy/enterprise/compose.json config --quiet
docker compose --project-name lesson24-enterprise --env-file /absolute/private.env -f deploy/enterprise/compose.json build
docker compose --project-name lesson24-enterprise --env-file /absolute/private.env -f deploy/enterprise/compose.json up -d
```

The private env must define digest variables plus POSTGRES_USER, POSTGRES_PASSWORD, LESSON17_INTERNAL_SERVICE_TOKEN, SPRING_AI_DASHSCOPE_API_KEY and the six LESSON12_EMBEDDING_* values (ENDPOINT,API_KEY,MODEL,REVISION,DIMENSIONS; mode is fixed http). Reranking is fixed rule for this baseline. No .env with actual values belongs in Git; do not print `compose config` without --quiet. Shell startup errors only print fixed codes; Java logs are not scrubbed by this wrapper. Test/model credentials are injected, not image build arguments.

main/worker/mcp use the same Java image. main owns a network namespace and only publishes 127.0.0.1:5215 (override FRONTEND_PORT) to nginx port80. Worker, MCP and nginx join service:main so fixed authenticated loopback origins remain valid. PG is an isolated network service, no default host publication. This supports one same-host deployment group, not independently scaled replicas. Main replacement may require recreating namespace-sharing dependants; verify their connectivity after restart.

nginx preserves Host including published port for same-origin CSRF, disables SSE buffering, and routes /api to loopback18301. This is plain HTTP on loopback; it does not prove external TLS. Frontend and main share exposure but not filesystems. Java runs UID10001; conversations share a writable named volume at /data/conversations. PG has its own named volume. Never use down -v for normal restarts. The initial database user owns migrations in this baseline; least-privilege runtime mode is a separate tracked change, not claimed by this Compose.

The staging tool copies only the selected JAR, frontend dist and checked-in entrypoint/nginx config; it excludes source directories and credential files. It rejects dist links and checks the JAR digest. Dist itself must be a trusted clean build; its content is not a credential scanner. Readiness is not guaranteed by service_started; parent acceptance must poll authenticated HTTP/MCP/Worker paths. PG healthcheck only means PG accepts connections. Compose has no automatic model calls or identity provisioning. Use an isolated test tenant and actual independent PG measurements.

Tests: `node --test deploy/enterprise/container.test.mjs` (5 passed). RED missing module observed; staging initially hit Windows cpSync EIO and was corrected to checked recursive file copies. Structure tests are not Docker runtime evidence. Actual builds/up, missing-env shell behavior, network isolation, migration, restart and browser screenshots must be recorded separately.

## Ordered restart of the shared network group

A simultaneous `compose restart main worker mcp frontend` can race: sidecars try joining a main namespace which is not running yet. This is a single-host deployment group, not rolling high availability. Use:

```
node deploy/enterprise/restart-apps.mjs --project lesson24-enterprise --env-file /absolute/private.env --compose deploy/enterprise/compose.json --origin http://127.0.0.1:5215
```

Repeat `--compose` for each existing override file, preserving the exact original configuration set. The helper stops frontend/mcp/worker, restarts only main, polls HTTP200 inside main via the tested Temurin bash `/dev/tcp`, then `up -d --no-build --no-deps` starts the three dependants, followed by host HTTP200. No build, `down`, volume deletion or database restart. Every subprocess has a 15-second timeout and each readiness check has a finite attempt limit (default30). Failures stop the sequence with a fixed code; the group can intentionally remain partially stopped for diagnosis. Command output is suppressed to avoid printing resolved environment configuration; inspect service logs separately without disclosing credentials.

This only verifies main authentication endpoint and nginx proxy HTTP200, not Worker/MCP readiness, user authentication, model transport or complete business functionality. After restart refresh MCP discovery with a maintainer account, confirm Worker/MCP status and read prior reports/PG receipts independently. Injected-runner tests cover ordering, stop failure, bounded internal/host timeouts and unsafe arguments; parent runtime evidence is recorded separately.

Staging output containment is checked before any output directory is created: the dist path and existing output parent are resolved with realpath, then platform path.relative compares the canonical paths (case-folded on Windows). An output inside dist or equal to dist is rejected even through a parent junction or Windows case variation. The output parent must already exist. Pure path tests reproduce case variants without invoking unsafe recursive copying; a fresh temporary junction integration test verifies rejection leaves no output directory. This is a local staging guard, not protection against concurrent privileged filesystem replacement.
