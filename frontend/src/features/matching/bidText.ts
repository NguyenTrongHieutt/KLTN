import { uploadMessage } from '@/features/files/uploadRules'
import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { problemMessage, type ProblemLink } from '@/lib/problemMessage'
import type { Application } from './applicationApi'

/** The `VALIDATION` reasons of a bid that say themselves (SG-58, AD-21): the shared text has no reason. */
const BID_VALIDATION = ['PROOF_LIMIT', 'LINK_INVALID', 'PROOF_FILE_UNKNOWN', 'PUBLIC_CODE_NOT_ACKNOWLEDGED']

const MATCHING_REASONS = (vi as { reason: { matching: Record<string, string> } }).reason.matching

/** What a bid's status says on MEN-3 and FND-1 (EXPERIENCE.md Surface states). */
export function bidStateText(application: Application): string {
  switch (application.status) {
    case 'SUBMITTED':
      return t('screen.matching.applied.sentOn', { date: formatDayMonth(application.submittedAt) })
    case 'ON_HOLD':
      return t('screen.matching.applied.onHold')
    case 'SELECTED':
      return t('screen.matching.applied.selected')
    default:
      return t(`screen.matching.applied.ended${application.status}` as MessageKey)
  }
}

/** A refused bid in plain words (FR-13): the Job limit names the limit, and too many today names when to try again. */
export function bidRefusal(error: unknown): string {
  if (error instanceof ApiError) {
    const reason = error.problem.reason
    if (reason === 'MENTOR_JOB_LIMIT') {
      const limit = (error.problem as { limit?: number }).limit ?? 3
      return t('screen.jobPost.jobLimit', { n: limit })
    }
    if (error.status === 429) {
      const at = new Date(Date.now() + (error.retryAfter ?? 0) * 1000).toISOString()
      return t('screen.matching.apply.tooMany', { time: formatDayMonthTime(at) })
    }
    if (reason && BID_VALIDATION.includes(reason)) {
      return t(`reason.matching.${reason}` as MessageKey)
    }
  }
  return problemMessage(error, { module: 'matching' }).text
}

/** Where a refused bid leads: ACC-1 for a profile with no display name yet (Story 19.7), else the shared way forward. */
export function bidRefusalLink(error: unknown): ProblemLink | undefined {
  if (error instanceof ApiError && error.problem.reason === 'PROFILE_REQUIRED') {
    return { href: '/ho-so', label: t('screen.matching.apply.profileLink') }
  }
  return problemMessage(error, { module: 'matching' }).link
}

/** A refused proof upload in plain words: the bid's reason, or else the shared upload text (NFR-3). */
export function proofUploadRefusal(error: unknown): string {
  const reason = error instanceof ApiError ? error.problem.reason : undefined
  return reason && reason in MATCHING_REASONS ? bidRefusal(error) : uploadMessage(error)
}
