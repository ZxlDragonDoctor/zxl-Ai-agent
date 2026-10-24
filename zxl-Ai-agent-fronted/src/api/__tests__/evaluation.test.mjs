import test from 'node:test'
import assert from 'node:assert/strict'
import { seedEvaluation, runEvaluation } from '../evaluation.ts'
test('seeding is an explicit action and carries the chosen model mode', async () => {
  const original=globalThis.fetch;let called
  globalThis.fetch=async (url,options)=>{called={url,body:JSON.parse(options.body)};return {ok:true,json:async()=>({snapshotReady:true})}}
  try {await seedEvaluation(true);assert.equal(called.url,'/api/ai/learning/knowledge/evaluation/seed');assert.deepEqual(called.body,{real:true})} finally {globalThis.fetch=original}
})
test('evaluation keeps split and K explicit without granting client access scope', async () => {
  const original=globalThis.fetch;let called
  globalThis.fetch=async (url,options)=>{called={url,body:JSON.parse(options.body)};return {ok:true,json:async()=>({complete:true})}}
  try {await runEvaluation('holdout',3,false);assert.equal(called.url,'/api/ai/learning/knowledge/evaluation/run');assert.deepEqual(called.body,{split:'holdout',topK:3,real:false,budgetMs:60000})} finally {globalThis.fetch=original}
})
