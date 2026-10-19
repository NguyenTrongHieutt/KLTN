import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'

const IDENTITY_REASONS = (vi as { reason: { identity: Record<string, string> } }).reason.identity

/** The `identity` reason of a refusal, when `vi.json` has its sentence. */
export function identityReason(error: unknown): string | undefined {
  const reason = error instanceof ApiError ? error.problem.reason : undefined
  return reason && reason in IDENTITY_REASONS ? reason : undefined
}

/**
 * What a refused send or code entry says (EXPERIENCE.md, Messages from Error Codes): an `identity`
 * reason in its own words, `RATE_LIMITED` with the time from `Retry-After`, anything else as the
 * shared mapping says.
 */
export function verificationMessage(error: unknown, now: Date = new Date()): string {
  const reason = identityReason(error)
  if (reason) {
    return t(`reason.identity.${reason}` as MessageKey)
  }
  if (error instanceof ApiError && error.problem.code === 'RATE_LIMITED' && error.retryAfter) {
    const time = formatDayMonthTime(new Date(now.getTime() + error.retryAfter * 1000).toISOString())
    return problemMessage(error, { time }).text
  }
  return problemMessage(error).text
}
