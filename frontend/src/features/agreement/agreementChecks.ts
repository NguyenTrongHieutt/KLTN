/**
 * The editor's live checks (FR-16, UX-DR34), the same rules the server applies when sharing, so
 * "Gửi cho khách hàng" waits until nothing is missing.
 */
export interface DraftCriterion {
  id: string
  statement: string
  checkSteps: string
}

export interface DraftMilestone {
  id: string
  title: string
  price: string
  dueOn: string
  revisionRounds: number
  criteria: DraftCriterion[]
}

export interface DraftAccess {
  id: string
  system: string
  purpose: string
  environment: 'NON_PRODUCTION' | 'PRODUCTION'
  accessLevel: 'READ' | 'WRITE' | 'ADMIN'
}

export type ProblemCode =
  | 'SCOPE_MISSING'
  | 'PRICE_MISSING'
  | 'MILESTONE_MISSING'
  | 'MILESTONE_INCOMPLETE'
  | 'MILESTONE_WITHOUT_CRITERION'
  | 'CRITERION_WITHOUT_STEPS'
  | 'PRICE_SUM_MISMATCH'
  | 'EXTERNAL_ACCESS_INCOMPLETE'

/** A problem with the 1-based numbers its sentence names. */
export interface DraftProblem {
  code: ProblemCode
  milestone?: number
  criterion?: number
  access?: number
}

export function digits(text: string): number | undefined {
  const plain = text.replace(/\D/g, '')
  return plain === '' ? undefined : Number(plain)
}

export function problemsOf(
  draft: { scope: string; price: string; milestones: DraftMilestone[]; access: DraftAccess[] },
  assessment: boolean,
): DraftProblem[] {
  const problems: DraftProblem[] = []
  if (!assessment && draft.scope.trim() === '') {
    problems.push({ code: 'SCOPE_MISSING' })
  }
  const price = digits(draft.price)
  if (price === undefined) {
    problems.push({ code: 'PRICE_MISSING' })
  }
  if (draft.milestones.length === 0) {
    problems.push({ code: 'MILESTONE_MISSING' })
  }
  let sum = 0
  draft.milestones.forEach((milestone, m) => {
    if (milestone.title.trim() === '' || digits(milestone.price) === undefined || milestone.dueOn === '') {
      problems.push({ code: 'MILESTONE_INCOMPLETE', milestone: m + 1 })
    }
    if (milestone.criteria.length === 0) {
      problems.push({ code: 'MILESTONE_WITHOUT_CRITERION', milestone: m + 1 })
    }
    milestone.criteria.forEach((criterion, c) => {
      if (criterion.statement.trim() === '' || criterion.checkSteps.trim() === '') {
        problems.push({ code: 'CRITERION_WITHOUT_STEPS', milestone: m + 1, criterion: c + 1 })
      }
    })
    sum += digits(milestone.price) ?? 0
  })
  if (price !== undefined && draft.milestones.length > 0 && sum !== price) {
    problems.push({ code: 'PRICE_SUM_MISMATCH' })
  }
  draft.access.forEach((access, a) => {
    if (access.system.trim() === '' || access.purpose.trim() === '') {
      problems.push({ code: 'EXTERNAL_ACCESS_INCOMPLETE', access: a + 1 })
    }
  })
  return problems
}

/** Words a customer would not use to check their own app (FR-16 `[ASSUMPTION]`). */
const TECHNICAL = new Set([
  'api', 'sql', 'database', 'db', 'constraint', 'unique', 'index', 'endpoint', 'migration', 'schema', 'query', 'token',
  'jwt', 'webhook', 'component', 'props', 'cache', 'deploy', 'server', 'backend', 'frontend', 'rls', 'policy',
  'trigger', 'function', 'null', 'regex', 'json', 'crud', 'orm', 'table', 'column',
])

/** Words that tell what a person sees or does, which a plain criterion has. */
const PLAIN = new Set(['khách', 'người', 'thấy', 'bấm', 'nhận', 'hiện', 'mở', 'chọn', 'đặt', 'xem', 'gửi', 'báo', 'trang', 'nút'])

/**
 * A criterion written only in technical terms: it names technical things and nothing a person sees.
 * Words are split on spaces and punctuation, since `\b` knows no Vietnamese letters.
 */
export function technicalOnly(statement: string): boolean {
  const words = statement.toLocaleLowerCase('vi').split(/[\s,.;:!?()"'/]+/).filter(Boolean)
  return words.some((word) => TECHNICAL.has(word)) && !words.some((word) => PLAIN.has(word))
}

/** The number of "how to check" steps: one per non-empty line or numbered step. */
export function stepCount(steps: string): number {
  const numbered = steps.match(/(^|\s)\d+\./g)
  if (numbered && numbered.length > 0) {
    return numbered.length
  }
  return steps.split('\n').filter((line) => line.trim() !== '').length
}
