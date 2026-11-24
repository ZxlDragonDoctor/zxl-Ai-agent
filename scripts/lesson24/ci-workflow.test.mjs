import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const workflow=()=>readFileSync(new URL('../../.github/workflows/enterprise-ci.yml',import.meta.url),'utf8');

test('CI has read-only permissions, pinned official actions and no production secrets or deploy credentials',()=>{
 const text=workflow();
 assert.match(text,/permissions:\s*\n\s+contents: read/);
 assert.ok(!text.includes('pull_request_target'));
 assert.ok(!/secrets\.|write-all|contents: write|id-token: write/.test(text));
 const uses=[...text.matchAll(/uses:\s*(\S+)/g)].map(x=>x[1]);
 const approved=JSON.parse(readFileSync(new URL('./ci-actions.json',import.meta.url),'utf8')).actions.map(a=>a.repository+'@'+a.sha);
 assert.ok(uses.length>=4);assert.ok(uses.every(x=>/^actions\/[a-z-]+@[a-f0-9]{40}$/.test(x)));
 assert.ok(uses.every(x=>approved.includes(x)));
 assert.match(text,/persist-credentials: false/);
 assert.ok(!/continue-on-error:\s*true|-DskipTests|testFailureIgnore/.test(text));
});
test('CI checks the inventory before all enterprise tests on a real pinned pgvector service',()=>{
 const text=workflow();
 assert.match(text,/pgvector\/pgvector@sha256:[a-f0-9]{64}/);
 assert.match(text,/LESSON12_DB_URL: jdbc:postgresql:\/\/127\.0\.0\.1:5432\/enterprise_ci/);
 assert.match(text,/mvn -B -ntp -Penterprise-ci verify/);
 const inventory=text.indexOf('java-test-inventory.mjs --check');assert.ok(inventory>=0&&inventory<text.indexOf('mvn -B'));
 assert.match(text,/timeout-minutes:/);
});
test('frontend, delivery tool tests and immutable artifact manifests are separate checks',()=>{
 const text=workflow();
 assert.match(text,/node --experimental-strip-types --test/);
 assert.match(text,/npm ci --no-audit --no-fund/);
 assert.match(text,/npm run build/);
 assert.match(text,/deploy\/enterprise\/\*\.test\.mjs/);
 assert.match(text,/ci-manifest.mjs --kind java --commit "\$GITHUB_SHA"/);
 assert.match(text,/ci-manifest.mjs --kind frontend --commit "\$GITHUB_SHA"/);
 assert.ok(!/path:\s*["']?\.(?:\s|$)|path:\s*target\/\*\*/m.test(text));
 assert.match(text,/target\/zxl-Ai-agent-0\.0\.1-SNAPSHOT\.jar/);
 assert.match(text,/zxl-Ai-agent-fronted\/dist/);
});
