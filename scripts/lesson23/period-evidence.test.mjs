import test from 'node:test';
import assert from 'node:assert/strict';
import {budgetSnapshotSql, freshTenantSql, verifyPeriodSnapshot} from './period-evidence.mjs';
const tenant='lesson23-period-fixture',run='01d6262d-e011-495b-a9aa-cf3495656df4';
const sample=()=>({periodId:'2026-11-23',databaseUtcDate:'2026-11-23',reserved:0,spent:100,calls:2,period:{reserved:0,spent:100,calls:2},tenantRunCount:1});
test('new tenant period matches independent run totals and database UTC date',()=>assert.doesNotThrow(()=>verifyPeriodSnapshot(sample(),'2026-11-23')));
test('reject missing period, totals, extra run and wrong day',()=>{for(const mutate of [s=>s.period=null,s=>s.period.spent=99,s=>s.period.calls=1,s=>s.period.reserved=1,s=>s.tenantRunCount=2,s=>s.periodId='2026-11-21',s=>s.spent=null,s=>s.calls=-1]){const s=sample();mutate(s);assert.throws(()=>verifyPeriodSnapshot(s,'2026-11-23'));}});
test('midnight boundary accepts server date before or after request',()=>{const s=sample();s.periodId='2026-11-22';assert.doesNotThrow(()=>verifyPeriodSnapshot(s,'2026-11-22'));});
test('queries restrict trusted fixture identifiers and query database UTC clock',()=>{assert.match(budgetSnapshotSql(tenant,run),/token_budget_period/);assert.match(freshTenantSql(tenant),/AT TIME ZONE 'UTC'/);assert.throws(()=>budgetSnapshotSql("x'; SELECT 1;--",run));assert.throws(()=>budgetSnapshotSql(tenant,'oops'));});
