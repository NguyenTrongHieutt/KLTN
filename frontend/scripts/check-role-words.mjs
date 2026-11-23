// UX-DR109 (R16, Story 20.6): no text calls a person "sinh viên"; the word survives only in the badge "Sinh viên đã
// xác minh". Run by `npm run lint`. A self-test proves the check still fires.
// The plain-term check (NFR-12) that used to run next to it was retired on 2026-11-23: the UI now uses technical
// terms as they are (EXPERIENCE.md, "UX changes of 2026-11-23").
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/** The one place the word "Sinh viên" may appear (R16): the badge of a verified student email. */
export const STUDENT_BADGE = 'Sinh viên đã xác minh'

/**
 * R16: the role words are Khách hàng, Người hướng dẫn, Thành viên nhóm and Nhóm thực hiện; "sinh viên" is left in no
 * text of vi.json, terms included, but the badge "Sinh viên đã xác minh".
 */
export function checkRoleWords(vi) {
  const violations = []
  const strings = []
  collectAll(vi, '', strings)
  const badge = new RegExp(escape(STUDENT_BADGE), 'giu')
  for (const [key, text] of strings) {
    if (/sinh viên/iu.test(text.replace(badge, ' '))) {
      violations.push(`${key}: "sinh viên" names only the badge "${STUDENT_BADGE}" (R16); say "thành viên nhóm" or "người hướng dẫn"`)
    }
  }
  return violations
}

function collectAll(node, prefix, out) {
  for (const [key, value] of Object.entries(node)) {
    const dotted = prefix ? `${prefix}.${key}` : key
    if (typeof value === 'string') {
      out.push([dotted, value])
    }
    else if (value && typeof value === 'object') {
      collectAll(value, dotted, out)
    }
  }
}

function escape(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function selfTest() {
  const failures = []
  const words = [
    ['a person called a student', { screen: { crew: { add: 'Thêm sinh viên' } } }, 1],
    ['a student term', { term: { student: { label: 'Sinh viên' } } }, 1],
    ['the badge', { screen: { profile: { badge: 'Sinh viên đã xác minh · HUST' } } }, 0],
    ['the badge in a sentence', { notification: { x: { title: 'Huy hiệu Sinh viên đã xác minh hết hạn' } } }, 0],
    ['the role words', { screen: { crew: { add: 'Thêm thành viên nhóm' } } }, 0],
  ]
  for (const [name, vi, expected] of words) {
    const found = checkRoleWords(vi).length
    if (found !== expected) {
      failures.push(`self-test "${name}": expected ${expected} R16 violation(s), found ${found}`)
    }
  }
  return failures
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  const vi = JSON.parse(readFileSync(path.join(root, 'src/i18n/vi.json'), 'utf8'))
  const problems = [...selfTest(), ...checkRoleWords(vi)]
  if (problems.length > 0) {
    console.error(problems.join('\n'))
    process.exit(1)
  }
  console.log('Role-word check (R16): no text calls a person "sinh viên" outside the badge.')
}
