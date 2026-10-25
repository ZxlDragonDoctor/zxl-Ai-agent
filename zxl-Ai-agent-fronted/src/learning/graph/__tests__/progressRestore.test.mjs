import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
const raw = JSON.parse(fs.readFileSync(new URL('../catalogue.json', import.meta.url), 'utf8'))
function api(current) {
  let saved = JSON.stringify(current)
  const storage = { getItem: () => saved, setItem: (_key, value) => { saved = value } }
  const source = fs.readFileSync(new URL('../model.ts', import.meta.url), 'utf8')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', 'localStorage', js)(() => raw, module, module.exports, storage)
  return module.exports
}
const record = (note) => ({ state: 'notStarted', note, scrollTop: 0, updatedAt: '2026-10-25T00:00:00Z' })
test('normal import merges and preserves existing unrelated personal notes', () => {
  const current = { schemaVersion: 1, records: { 'lesson-01': record('original'), 'lesson-02': record('keep me') } }
  const backup = { schemaVersion: 1, records: { 'lesson-01': record('backup') } }
  assert.deepEqual(api(current).importLearningProgress(JSON.stringify(backup)).records, { ...current.records, ...backup.records })
})
test('explicit backup restore removes only entries absent from the selected backup', () => {
  const current = { schemaVersion: 1, records: { 'lesson-01': record('changed'), 'lesson-02': record('test reading') } }
  const backup = { schemaVersion: 1, records: { 'lesson-01': record('original') } }
  assert.deepEqual(api(current).importLearningProgress(JSON.stringify(backup), 'replace'), backup)
})
test('unsupported restore mode fails without modifying stored progress', () => {
  const current = { schemaVersion: 1, records: { 'lesson-01': record('original') } }
  const model = api(current)
  assert.throws(() => model.importLearningProgress(JSON.stringify(current), 'unknown'))
  assert.deepEqual(model.loadProgress(), current)
})
