import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from './apiClient'

/** A way forward a screen shows beside the sentence. */
export interface ProblemLink {
  href: string
  label: string
}

/** What the user reads for a failed request: the sentence, for a server error its reference, and a way forward. */
export interface ProblemText {
  text: string
  reference?: string
  /** `GITHUB_NOT_LINKED`, from any module: ACC-2, where GitHub is linked (FR-80). */
  link?: ProblemLink
}

/** Reasons any module's command may answer, read from `reason.identity` when the module has no text of its own. */
const IDENTITY_REASONS = ['GITHUB_NOT_LINKED']

/** The calling screen's context: its module for the reason, and the words the patterns need. */
export interface ProblemContext {
  /** The module whose reason enum the `reason` belongs to (`reason.{module}.{REASON}`). */
  module?: string
  /** For `GUARD_FAILED`: what the user tried, for example "ứng tuyển". */
  action?: string
  /** For `JOB_BUSY`: the running operation, for example "việc hợp nhất". */
  operation?: string
  /** For `RATE_LIMITED`: when to try again, from `Retry-After`. */
  time?: string
  /** The message catalog; tests pass their own. */
  messages?: { reason?: Record<string, Record<string, string>> }
}

const CODES = ['VALIDATION', 'NOT_FOUND', 'FORBIDDEN', 'STALE', 'JOB_BUSY', 'GUARD_FAILED', 'RATE_LIMITED', 'CONFLICT']

/**
 * The single mapping from an API error to Vietnamese (AD-23, EXPERIENCE.md "Messages from Error
 * Codes"). A code never appears as text: an unknown code, a server error, or a failure without an
 * answer reads "Đã có lỗi. Hãy thử lại sau ít phút.", and a 5xx adds its correlation ID as the
 * reference for support.
 */
export function problemMessage(error: unknown, context: ProblemContext = {}): ProblemText {
  if (!(error instanceof ApiError)) {
    return unexpected()
  }
  if (error.status === 401) {
    return { text: t('error.UNAUTHENTICATED') }
  }
  const { code, reason, errors, correlationId } = error.problem
  if (error.status >= 500 || !code || !CODES.includes(code)) {
    return unexpected(error.status >= 500 ? correlationId : undefined)
  }
  const found = reason ? reasonFor(context, reason) : undefined
  // A repository check's reason names the repository it refused (`{repo}`, Story 23.5).
  const repository = error.problem.repository
  const reasonText = found && repository ? found.replace(/\{repo\}/g, repository) : found
  if (reason === 'GITHUB_NOT_LINKED') {
    const text = reasonText ? `${t('error.FORBIDDEN')} ${sentence(reasonText)}` : t('error.FORBIDDEN')
    return { text, link: { href: '/ho-so/xac-minh', label: t('error.githubLink') } }
  }
  switch (code) {
    case 'VALIDATION':
      return { text: t('error.VALIDATION', { n: errors?.length ?? 1 }) }
    case 'JOB_BUSY':
      return { text: t('error.JOB_BUSY', { operation: context.operation ?? '' }) }
    case 'GUARD_FAILED':
      if (context.action && reasonText) {
        return { text: t('error.GUARD_FAILED', { action: context.action, reason: reasonText }) }
      }
      return { text: reasonText ? sentence(reasonText) : t('error.FORBIDDEN') }
    case 'RATE_LIMITED':
      return { text: t('error.RATE_LIMITED', { time: context.time ?? '' }) }
    case 'FORBIDDEN':
    case 'CONFLICT':
      return { text: reasonText ? `${t(`error.${code}`)} ${sentence(reasonText)}` : t(`error.${code}`) }
    default:
      return { text: t(`error.${code}` as MessageKey) }
  }
}

/**
 * The Vietnamese sentence for one module's reason, for a state a screen reads rather than a failed request
 * (AD-23, Story 23.8a): `undefined` when the catalog holds no text for it, so the caller shows its own fallback
 * and never the raw code.
 */
export function reasonMessage(module: string, reason: string): string | undefined {
  return reasonFor({ module }, reason)
}

/** Whether the answer is `NOT_FOUND`, which a screen shows as the one not-found screen (SG-31). */
export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.problem.code === 'NOT_FOUND'
}

function unexpected(correlationId?: string): ProblemText {
  const text = t('error.unexpected')
  return correlationId ? { text, reference: t('error.reference', { correlationId }) } : { text }
}

function reasonFor(context: ProblemContext, reason: string): string | undefined {
  const catalog = context.messages ?? (vi as { reason?: Record<string, Record<string, string>> })
  const own = context.module ? catalog.reason?.[context.module]?.[reason] : undefined
  return own ?? (IDENTITY_REASONS.includes(reason) ? catalog.reason?.identity?.[reason] : undefined)
}

/** A lower-case reason fragment as a sentence of its own. */
export function sentence(fragment: string): string {
  const text = fragment.charAt(0).toLocaleUpperCase('vi') + fragment.slice(1)
  return /[.!?…]$/.test(text) ? text : `${text}.`
}
