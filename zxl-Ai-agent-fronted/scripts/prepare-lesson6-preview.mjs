import { mkdir, copyFile } from 'node:fs/promises'

const cases = ['branch-failure', 'success', 'synthesis-failure', 'planning-failure']
await mkdir(new URL('../dev/lesson6/', import.meta.url), { recursive: true })
for (const name of cases) {
  await copyFile(new URL(`../../target/lesson6-ui/${name}.json`, import.meta.url),
    new URL(`../dev/lesson6/${name}.generated.json`, import.meta.url))
}
console.log('Lesson six reports prepared for /dev/lesson6/. Run the frontend development server to view them.')
