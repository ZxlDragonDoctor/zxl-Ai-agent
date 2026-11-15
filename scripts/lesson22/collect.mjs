import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

export function collect(dataset, split, digest, inputs) {
  if (!['dev', 'holdout'].includes(split) || !inputs.length) throw new Error('Provide a split and evidence files')
  const cases = dataset.cases.filter(item => item.split === split)
  const ids = new Set(cases.map(item => item.id))
  if (!ids.size || ids.size !== cases.length) throw new Error('Invalid cases for the selected split')
  const metadata = { datasetSha256: digest }
  for (const key of ['model', 'knowledgeVersion', 'variant']) {
    const value = inputs[0][key]
    if (typeof value !== 'string' || !value.trim()) throw new Error('Missing ' + key)
    metadata[key] = value
  }
  const runs = new Map()
  const runIds = new Set()
  for (const input of inputs) {
    for (const key of Object.keys(metadata)) {
      if (metadata[key] !== input[key]) throw new Error('Evidence mismatch: ' + key)
    }
    if (!Array.isArray(input.runs)) throw new Error('Invalid evidence rows')
    for (const run of input.runs) {
      if (!ids.has(run.id)) throw new Error('Case is outside the selected split: ' + run.id)
      if (runs.has(run.id)) throw new Error('Duplicate case: ' + run.id)
      if (run.runId && runIds.has(run.runId)) throw new Error('Duplicate run: ' + run.runId)
      if (run.runId) runIds.add(run.runId)
      runs.set(run.id, run)
    }
  }
  return { ...metadata, runs: cases.flatMap(item => runs.has(item.id) ? [runs.get(item.id)] : []) }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [datasetPath, split, ...files] = process.argv.slice(2)
  const raw = readFileSync(datasetPath)
  const digest = createHash('sha256').update(raw).digest('hex')
  const inputs = files.map(file => JSON.parse(readFileSync(file)))
  console.log(JSON.stringify(collect(JSON.parse(raw), split, digest, inputs), null, 2))
}
