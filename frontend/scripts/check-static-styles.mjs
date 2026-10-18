// UX-DR2, AD-21: the CSP is `default-src 'self'` with no style-src, so no code may create a <style>
// element or a style sheet at run time; only the static CSS of the Tailwind build is allowed. Run
// by `npm run build` on the built bundle: sonner, a Base UI component that hoists a <style> (Select,
// ScrollArea), or any CSS-in-JS library fails the build. Inline `style` props are allowed, because
// React sets them through the CSSOM, which the CSP does not block. A self-test proves it fires.
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const CREATE_STYLE = 'createElement("style")'

export const PATTERNS = [
  { name: CREATE_STYLE, regex: /createElement\(\s*["'`]style["'`]\s*\)/g },
  { name: 'a JSX <style> element', regex: /[(,]\s*["'`]style["'`]\s*,\s*\{/g },
  { name: 'insertRule', regex: /\.insertRule\(/g },
  { name: 'new CSSStyleSheet', regex: /new\s+CSSStyleSheet\b/g },
  { name: 'adoptedStyleSheets', regex: /adoptedStyleSheets/g },
]

/**
 * React DOM's own code for a hoisted `<style precedence>` element creates it with
 * `createElement("style")` right after writing `data-precedence`. That code runs only when a
 * component renders such an element, and the JSX pattern catches that component, so this one match
 * is React's and is skipped.
 */
const REACT_HOISTING = /["'`]data-precedence["'`]/

export function check(files) {
  const violations = []
  for (const [file, code] of files) {
    for (const pattern of PATTERNS) {
      for (const match of code.matchAll(pattern.regex)) {
        const before = code.slice(Math.max(0, match.index - 400), match.index)
        if (pattern.name === CREATE_STYLE && REACT_HOISTING.test(before)) {
          continue
        }
        violations.push(`${file}: creates styles at run time (${pattern.name}); the CSP blocks it`)
        break
      }
    }
  }
  return violations
}

function selfTest() {
  const cases = [
    ['document.createElement("style")', 'var s=document.createElement("style");', 1],
    ['a hoisted JSX style', 'e(t,{children:a}),n("style",{href:"x",precedence:"low",children:".x{}"})', 1],
    ['a hoisted JSX style in backticks', 'n(`style`,{href:`x`,precedence:`low`,children:`.x{}`})', 1],
    ['React DOM creating a hoisted style', 'T({},n,{"data-href":n.href,"data-precedence":n.precedence}),r=e.createElement(`style`)', 0],
    ['adoptedStyleSheets', 'document.adoptedStyleSheets=[s]', 1],
    ['insertRule', 'sheet.insertRule(".a{}",0)', 1],
    ['an inline style prop', 'n("div",{style:{width:4}})', 0],
    ['the word style in text', 'var title="style guide";', 0],
  ]
  const failures = []
  for (const [name, code, expected] of cases) {
    const found = check([['probe.js', code]]).length
    if (found !== expected) {
      failures.push(`self-test "${name}": expected ${expected} violation(s), found ${found}`)
    }
  }
  return failures
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  const assets = path.join(root, 'dist', 'assets')
  const files = readdirSync(assets)
    .filter((name) => name.endsWith('.js'))
    .map((name) => [`dist/assets/${name}`, readFileSync(path.join(assets, name), 'utf8')])
  const empty = files.length === 0 ? ['dist/assets holds no JavaScript; run vite build first'] : []
  const problems = [...selfTest(), ...empty, ...check(files)]
  if (problems.length > 0) {
    console.error(problems.join('\n'))
    process.exit(1)
  }
  console.log(`Static-style check: ${files.length} bundle file(s) create no styles at run time.`)
}
