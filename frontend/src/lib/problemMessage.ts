import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from './apiClient'

/** What the user reads for a failed request: the sentence, and for a server error its reference. */
export interface ProblemText {
  text: string
  reference?: string
}

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
  const reasonText = reason ? reasonFor(context, reason) : undefined
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
  return context.module ? catalog.reason?.[context.module]?.[reason] : undefined
}

/** A lower-case reason fragment as a sentence of its own. */
function sentence(fragment: string): string {
  const text = fragment.charAt(0).toLocaleUpperCase('vi') + fragment.slice(1)
  return /[.!?…]$/.test(text) ? text : `${text}.`
}
