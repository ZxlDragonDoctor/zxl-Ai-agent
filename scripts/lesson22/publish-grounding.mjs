import { readFileSync } from 'node:fs'
import { formalSession } from './formal-session.mjs'

const [fixtureFile, documentId] = process.argv.slice(2)
const { LESSON22_ORIGIN, LESSON22_TENANT, LESSON22_MAINTAINER, LESSON22_MAINTAINER_PASSWORD } = process.env
if (!/^lesson22-[a-z0-9-]+$/.test(LESSON22_TENANT ?? '')) {
  throw new Error('Use a dedicated lesson22 tenant for fixture publication')
}
const entries = JSON.parse(readFileSync(fixtureFile))
const matches = entries.filter(item => item.documentId === documentId)
if (matches.length !== 1) throw new Error('Choose exactly one indexed fixture document')
const request = await formalSession(LESSON22_ORIGIN, LESSON22_TENANT, LESSON22_MAINTAINER,
  LESSON22_MAINTAINER_PASSWORD, 'knowledge-maintainer')
await request('/ai/learning/knowledge/documents', matches[0])
console.log('Published fixture: ' + documentId)
