// Self-test of the ESLint bans (AD-21, AD-23), run by `npm run lint`: lints small snippets as if
// they were files under src/ and fails if a ban stops firing, or fires where it is allowed.
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ESLint } from 'eslint'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const BANS = ['no-restricted-syntax', 'no-restricted-globals', 'no-restricted-properties', 'no-restricted-imports']

const cases = [
  {
    name: 'dangerouslySetInnerHTML as a JSX attribute',
    file: 'src/features/probe/Probe.tsx',
    code: "export function Probe() {\n  return <div dangerouslySetInnerHTML={{ __html: '<b>x</b>' }} />\n}\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'dangerouslySetInnerHTML as an object key',
    file: 'src/features/probe/probe.ts',
    code: "export const props = { dangerouslySetInnerHTML: { __html: '<b>x</b>' } }\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'dangerouslySetInnerHTML as a quoted object key',
    file: 'src/features/probe/probe.ts',
    code: "export const props = { 'dangerouslySetInnerHTML': { __html: '<b>x</b>' } }\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'dangerouslySetInnerHTML in a JSX file',
    file: 'src/features/probe/Probe.jsx',
    code: "export function Probe() {\n  return <div dangerouslySetInnerHTML={{ __html: '<b>x</b>' }} />\n}\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'dangerouslySetInnerHTML inside src/lib',
    file: 'src/lib/Probe.tsx',
    code: "export function Probe() {\n  return <div dangerouslySetInnerHTML={{ __html: '<b>x</b>' }} />\n}\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'fetch outside src/lib',
    file: 'src/features/probe/probe.ts',
    code: "export const response = fetch('/api/v1/environment')\n",
    expect: 'no-restricted-globals',
  },
  {
    name: 'window.fetch outside src/lib',
    file: 'src/features/probe/probe.ts',
    code: "export const response = window.fetch('/api/v1/environment')\n",
    expect: 'no-restricted-properties',
  },
  {
    name: 'openapi-fetch outside src/lib',
    file: 'src/features/probe/probe.ts',
    code: "import createClient from 'openapi-fetch'\nexport const client = createClient({})\n",
    expect: 'no-restricted-imports',
  },
  {
    name: 'fetch inside src/lib',
    file: 'src/lib/probe.ts',
    code: "export const response = fetch('/api/v1/environment')\n",
    expect: null,
  },
  {
    name: 'openapi-fetch inside src/lib',
    file: 'src/lib/probe.ts',
    code: "import createClient from 'openapi-fetch'\nexport const client = createClient({})\n",
    expect: null,
  },
]

const eslint = new ESLint({ cwd: root })
const failures = []
for (const testCase of cases) {
  const [result] = await eslint.lintText(testCase.code, { filePath: path.join(root, testCase.file) })
  const fatal = result.messages.filter((message) => message.fatal || message.ruleId === null)
  const fired = [...new Set(result.messages.map((message) => message.ruleId).filter((rule) => BANS.includes(rule)))]
  const expected = testCase.expect === null ? [] : [testCase.expect]
  if (fatal.length > 0) {
    failures.push(`${testCase.name}: could not lint (${fatal.map((message) => message.message).join('; ')})`)
  } else if (fired.join() !== expected.join()) {
    failures.push(`${testCase.name}: expected [${expected}] but got [${fired}]`)
  }
}

if (failures.length > 0) {
  console.error(`ESLint ban self-test failed:\n  ${failures.join('\n  ')}`)
  process.exit(1)
}
console.log(`ESLint ban self-test passed (${cases.length} cases).`)
