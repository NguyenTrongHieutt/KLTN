// NFR-12, UX-DR7: Customer-facing text never shows an untranslated technical term. Run by
// `npm run lint`. Every listed term a Customer may see (term.* in vi.json, its audience in
// term-audience.json) is removed from each Customer-facing string first; any technical word left
// fails the check. UX-DR109 (R16, Story 20.6): no text calls a person "sinh viên"; the word survives only in
// the badge "Sinh viên đã xác minh". A self-test proves both checks still fire.
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/** The key families Customers read (EXPERIENCE.md Foundation, Text). */
export const CUSTOMER_FACING = [
  'app',
  'environment',
  'state',
  'error',
  'reason',
  'shell',
  'notice',
  'screen.public',
  'screen.profile',
  'screen.shared',
]

/** Jargon a Customer screen must not show unless the Plain-Term List lists it. */
export const TECHNICAL = [
  'repository', 'repo', 'commit', 'branch', 'merge', 'pull request', 'push', 'fork', 'deploy', 'deployment',
  'token', 'api', 'webhook', 'server', 'backend', 'frontend', 'database', 'escrow', 'milestone', 'scan',
  'sandbox', 'bug', 'endpoint', 'oauth', 'json', 'sql', 'docker', 'pipeline', 'workflow', 'git', 'hash',
  'sha', 'staging', 'production', 'debug', 'payload', 'framework', 'config', 'cookie', 'session',
]

/** The one place the word "Sinh viên" may appear (R16): the badge of a verified student email. */
export const STUDENT_BADGE = 'Sinh viên đã xác minh'

/** The key families of the retired Teams, which Story 21.7 deletes with their screens. */
export const RETIRED_FAMILIES = ['screen.team', 'reason.team']

/** The label fields of a term entry that may appear on Customer screens. */
const LABEL_FIELDS = ['label', 'labelShort', 'labelAlt', 'labelCustomer', 'labelSteps', 'labelLedgerOnly']

export function check(vi, audience) {
  const violations = []
  const listed = []
  for (const [key, entry] of Object.entries(vi.term ?? {})) {
    const who = audience[key]
    if (!who) {
      violations.push(`term.${key} has no audience in term-audience.json`)
      continue
    }
    if (who.split(' ').includes('C')) {
      LABEL_FIELDS.forEach((field) => entry[field] && listed.push(entry[field]))
    }
  }
  for (const key of Object.keys(audience)) {
    if (!vi.term?.[key]) {
      violations.push(`term-audience.json names term.${key}, which vi.json does not have`)
    }
  }
  const strings = []
  collect(vi, '', strings)
  for (const [key, entry] of Object.entries(vi.term ?? {})) {
    if (audience[key]?.split(' ').includes('C') && entry.hint) {
      strings.push([`term.${key}.hint`, entry.hint])
    }
  }
  const byLength = [...listed].sort((a, b) => b.length - a.length)
  for (const [key, text] of strings) {
    // A {placeholder} is filled with an element (often the Term hint itself), not shown as text.
    let rest = text.replace(/\{\w+\}/g, ' ')
    for (const label of byLength) {
      rest = rest.replace(new RegExp(escape(label), 'giu'), ' ')
    }
    for (const word of TECHNICAL) {
      if (new RegExp(`(?<![\\p{L}\\p{N}])${escape(word)}(?![\\p{L}\\p{N}])`, 'iu').test(rest)) {
        violations.push(`${key}: "${word}" is not in the Plain-Term List; use a listed term or explain it`)
      }
    }
  }
  return violations
}

/**
 * R16 (UX-DR109): the role words are Khách hàng, Người hướng dẫn, Thành viên nhóm and Nhóm thực hiện; "sinh viên"
 * is left in no text of vi.json, terms included, but the badge "Sinh viên đã xác minh" and the retired Team
 * families.
 */
export function checkRoleWords(vi) {
  const violations = []
  const strings = []
  collectAll(vi, '', strings)
  const badge = new RegExp(escape(STUDENT_BADGE), 'giu')
  for (const [key, text] of strings) {
    if (RETIRED_FAMILIES.some((family) => key === family || key.startsWith(`${family}.`))) {
      continue
    }
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

function collect(node, prefix, out) {
  for (const [key, value] of Object.entries(node)) {
    const dotted = prefix ? `${prefix}.${key}` : key
    if (typeof value === 'string') {
      if (CUSTOMER_FACING.some((family) => dotted === family || dotted.startsWith(`${family}.`))) {
        out.push([dotted, value])
      }
    }
    else if (value && typeof value === 'object' && dotted !== 'term') {
      collect(value, dotted, out)
    }
  }
}

function escape(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function selfTest() {
  const audience = { khoMa: 'C', handoverCommit: 'M S A', khoaBiMat: 'C' }
  const vi = (screen) => ({
    screen: { profile: { probe: screen } },
    term: {
      khoMa: { label: 'kho mã', hint: 'Nơi lưu mã.' },
      handoverCommit: { label: 'commit bàn giao' },
      khoaBiMat: { label: 'khóa bí mật', labelAlt: 'khóa API' },
    },
  })
  const cases = [
    ['an unlisted English term', 'Kiểm tra repository của bạn.', 1],
    ['a listed term', 'Kiểm tra kho mã của bạn.', 0],
    ['a term listed for Mentors and Students only', 'Xem commit bàn giao.', 1],
    ['a listed term containing a technical word', 'Đổi khóa API ngay.', 0],
    ['a technical word inside another word', 'Lưu ý đăng ký.', 0],
    ['a placeholder named like a technical word', 'Bạn trả trước từng {milestone}.', 0],
  ]
  const failures = []
  for (const [name, text, expected] of cases) {
    const found = check(vi(text), audience).length
    if (found !== expected) {
      failures.push(`self-test "${name}": expected ${expected} violation(s), found ${found}`)
    }
  }
  if (check({ term: { x: { label: 'x' } } }, {}).length !== 1) {
    failures.push('self-test "a term without an audience": expected 1 violation')
  }
  const words = [
    ['a person called a student', { screen: { crew: { add: 'Thêm sinh viên' } } }, 1],
    ['a student term', { term: { student: { label: 'Sinh viên' } } }, 1],
    ['the badge', { screen: { profile: { badge: 'Sinh viên đã xác minh · HUST' } } }, 0],
    ['the badge in a sentence', { notification: { x: { title: 'Huy hiệu Sinh viên đã xác minh hết hạn' } } }, 0],
    ['a retired Team text', { screen: { team: { invite: 'Mời sinh viên' } } }, 0],
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
  const audience = JSON.parse(readFileSync(path.join(root, 'src/i18n/term-audience.json'), 'utf8'))
  const problems = [...selfTest(), ...check(vi, audience), ...checkRoleWords(vi)]
  if (problems.length > 0) {
    console.error(problems.join('\n'))
    process.exit(1)
  }
  console.log('Plain-term check: no unlisted technical term in Customer-facing text, and the R16 role words.')
}
