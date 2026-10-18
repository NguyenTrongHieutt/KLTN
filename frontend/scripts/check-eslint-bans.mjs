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
    name: 'dangerouslySetInnerHTML as a computed template-literal key',
    file: 'src/features/probe/probe.ts',
    code: "export const props = { [`dangerouslySetInnerHTML`]: { __html: '<b>x</b>' } }\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'innerHTML assignment',
    file: 'src/features/probe/probe.ts',
    code: "export function probe(element: HTMLElement) {\n  element.innerHTML = '<b>x</b>'\n}\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'outerHTML assignment',
    file: 'src/features/probe/probe.ts',
    code: "export function probe(element: HTMLElement) {\n  element.outerHTML = '<b>x</b>'\n}\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'insertAdjacentHTML',
    file: 'src/features/probe/probe.ts',
    code: "export function probe(element: HTMLElement) {\n  element.insertAdjacentHTML('beforeend', '<b>x</b>')\n}\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'document.write',
    file: 'src/features/probe/probe.ts',
    code: "export function probe() {\n  document.write('<b>x</b>')\n}\n",
    expect: 'no-restricted-syntax',
  },
  {
    name: 'textContent and other write() calls stay allowed',
    file: 'src/features/probe/probe.ts',
    code: "export function probe(element: HTMLElement, sink: { write(text: string): void }) {\n  element.textContent = 'x'\n  sink.write('x')\n}\n",
    expect: null,
  },
  {
    name: 'XMLHttpRequest outside src/lib',
    file: 'src/features/probe/probe.ts',
    code: 'export const request = new XMLHttpRequest()\n',
    expect: 'no-restricted-globals',
  },
  {
    name: 'EventSource outside src/lib',
    file: 'src/features/probe/probe.ts',
    code: "export const events = new EventSource('/api/v1/events')\n",
    expect: 'no-restricted-globals',
  },
  {
    name: 'WebSocket outside src/lib',
    file: 'src/features/probe/probe.ts',
    code: "export const socket = new WebSocket('wss://example.invalid')\n",
    expect: 'no-restricted-globals',
  },
  {
    name: 'navigator.sendBeacon outside src/lib',
    file: 'src/features/probe/probe.ts',
    code: "export const sent = navigator.sendBeacon('/api/v1/x')\n",
    expect: 'no-restricted-properties',
  },
  {
    name: 'openapi-fetch subpath outside src/lib',
    file: 'src/features/probe/probe.ts',
    code: "import createClient from 'openapi-fetch/dist/index.js'\nexport const client = createClient({})\n",
    expect: 'no-restricted-imports',
  },
  {
    name: 'a hand-written file in src/api is linted',
    file: 'src/api/extra.ts',
    code: "export const response = fetch('/api/v1/environment')\n",
    expect: 'no-restricted-globals',
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
