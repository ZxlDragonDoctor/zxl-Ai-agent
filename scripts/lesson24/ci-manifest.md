# CI artifact and report manifest

Run from the repository root after successful checks/build:

```sh
node scripts/lesson24/ci-manifest.mjs --kind java --commit <exact-40-character-HEAD> --output target/java-ci.json
node scripts/lesson24/ci-manifest.mjs --kind frontend --commit <exact-40-character-HEAD> --output target/frontend-ci.json
node --test scripts/lesson24/ci-manifest.test.mjs
```

The requested commit must equal Git HEAD. Both staged and unstaged tracked edits reject the manifest; the utility never resets, cleans or edits the worktree. Untracked build outputs are allowed. Output is an exclusive new `target/*.json`; parent target links/junctions and nested/escaping output paths reject.

Java reads only the fixed `target/zxl-Ai-agent-0.0.1-SNAPSHOT.jar` and `target/surefire-reports/TEST-*.xml`. It records artifact/report paths, byte sizes and SHA256 plus **root testsuite** counts. Missing/zero reports or failed/error counts reject. Skipped cases remain visible, with executed=reported tests-skipped; this is not coverage, proof that every test ran, or evidence that an old report belongs to the current build. CI must start from a clean checkout and perform the actual commands before creating this record.

Frontend requires `zxl-Ai-agent-fronted/dist/index.html` and hashes the recursively sorted dist files, rejecting symlinks/junctions and other nonregular artifacts. It does not invent frontend test counts from build success.

This utility neither builds nor packages, scans JAR contents for local configuration, reads environment values, nor includes original report contents. It cannot certify the absence of secrets in a previously built artifact. CI should upload only explicit JAR/dist/manifest and intended reports, never all target data. Source checksums/test counters are separate from authenticated business, browser, database and real-model acceptance.
