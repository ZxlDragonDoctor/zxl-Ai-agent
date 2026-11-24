# Local TLS recovery deployment

`compose-tls-recovery.json` is a standalone five-service recovery topology. It never runs bootstrap/migration/grant tasks and never guesses the restored database name. Prepare the separate recovery PostgreSQL roles/database/ACLs and revoke copied sessions first, using the recovery procedure. Match the project and named volumes to that prepared destination; do not silently connect to a new empty volume.

Required configuration includes `RECOVERY_DB_NAME`, runtime credentials, trusted prebuilt Java/frontend images and `TLS_CERT_DIR`. This directory must already contain `cert.pem` and `key.pem`. All three nginx configuration/certificate binds are read-only, single-file bindings declaring `create_host_path:false`. Actual Docker Desktop testing nevertheless created two empty cert/key directories on a missing-file negative case before nginx failed: the mount flag alone is not a sufficient preflight. Keep private keys, CA keys and actual environment files outside Git, such as ignored target directories. Certificates are injected, not baked into the image.

```sh
docker compose --project-name lesson24-tls --env-file /absolute/private.env -f deploy/enterprise/compose-tls-recovery.json config --quiet
node deploy/enterprise/tls-start.mjs --project lesson24-tls --env-file /absolute/private.env --compose deploy/enterprise/compose-tls-recovery.json --tls-dir /absolute/private-tls --ca-file /absolute/private-tls/ca.pem
```

The only published application endpoint is `https://127.0.0.1:5443` by default (`TLS_PORT` overrides it). There is no HTTP listener or redirect service. nginx accepts only 127.0.0.1/localhost Host values, returning421 for other hosts. It enables TLS1.2/1.3 and serves the trusted static SPA. `/api` preserves the actual Host and overwrites client forwarding headers: HTTPS protocol, explicit external port (443 if absent), normalized host and remote address. SSE buffering/cache are disabled, with600s read timeout.

main uses Tomcat native forwarding with explicit protocol/port headers and a loopback-only internal-proxy regex. Worker/MCP/frontend share main's network namespace; backend ports remain loopback-only. Never replace the internal-proxy pattern with an empty trust-all value. A trusted proxy configuration is an application security boundary; do not publish the Java HTTP port or blindly reuse it with arbitrary external proxies.

This is a local SAN127.0.0.1/localhost learning exercise. The caller must generate and trust an appropriate local CA/certificate for the test client; a self-issued certificate is not public trust or automated certificate lifecycle management. No private key or CA is provided here. Never use global TLS verification disabling as proof that trust/hostname checks work.

TLS terminates at nginx. The shared-loopback Java hops and the separate PostgreSQL connection are not claimed to have end-to-end TLS by this configuration.

## Validated start entry point

`tls-start.mjs` accepts explicit project, env file, one or more `--compose` files, `--tls-dir` and optional `--ca-file`. Before any Docker command it checks existing readable regular cert/key files, rejects file/directory symlinks, parses the certificate/current validity, requires SAN127.0.0.1 and DNSlocalhost, parses the private key and compares public SPKI bytes. If a CA is supplied, it checks CA status/current validity and leaf issuer/signature. No certificate or directory is created by the tool.

Only after validation does it run the explicit Compose `up -d --no-build`, without down or volume deletion. The child environment forces `TLS_CERT_DIR` to the validated canonical directory, taking precedence over an env-file value; use the supplied trusted TLS Compose configuration and inspect any overrides separately. Docker output is suppressed and errors are fixed codes without PEM, private key or stack trace. The child command has a two-minute client timeout; interruption does not guarantee that a remote Docker action was canceled, so inspect the group before retrying.

Success means `status:started, readinessVerified:false`. Strict HTTPS trust/hostname, Secure cookies, Origin enforcement, streaming and browser flow are still independent acceptance checks. Five additional starter tests use actual filesystem negatives and an injected parsing/runner positive; they do not establish actual certificate or Docker behavior. Run `node --test deploy/enterprise/tls-start.test.mjs` and record real starts separately.

Five Node structure tests cover endpoint exposure, forwarding/trust settings, explicit restored DB, safe mounts and nginx streaming/host behavior. They are not actual nginx syntax, TLS negotiation, secure-cookie, origin, stream or browser evidence. Record real-container checks separately. The ordered restart helper now supports HTTPS with explicit `--ca-file`; it retains CA and hostname validation rather than ignoring certificates.

## Reproduce local certificate material

Create a new teaching directory; choose a new name if it already exists. This does not install a CA into the operating system. The fixed helper contains the OpenSSL3.5.5 used in acceptance. Keep every generated file in ignored target; never commit either private key.

```powershell
$taskTlsDirectory = (New-Item -ItemType Directory -Path target/local-tls-new -ErrorAction Stop).FullName
$taskTlsScript = @'
set -eu
umask 077
cd /certs
[ ! -e ca-key.pem ] && [ ! -e key.pem ] && [ ! -e cert.pem ]
cat > server.ext <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=IP:127.0.0.1,DNS:localhost
EXT
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca-key.pem -out ca.pem -days 7 -subj /CN=ZxlAgentLocalTeachingCA -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign,cRLSign
openssl req -new -newkey rsa:2048 -nodes -keyout key.pem -out server.csr -subj /CN=localhost
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -out cert.pem -days 3 -sha256 -extfile server.ext
openssl verify -CAfile ca.pem cert.pem
'@
docker run --rm --network none --entrypoint sh --mount "type=bind,src=$taskTlsDirectory,dst=/certs" eclipse-temurin@sha256:541b08fc7f494edf14e436ff6bfb55767e4a115c87bfc547b8c5de957b58737f -c $taskTlsScript
```

Supply that directory to tls-start and its public ca.pem to the strict probe. This is a short-lived direct local CA chain, not public-domain issuance, renewal or complete PKIX chain construction. Windows host ACLs must still protect private material; a container umask is not a claim about Windows access control.
