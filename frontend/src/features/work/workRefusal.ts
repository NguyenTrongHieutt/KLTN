import { uploadMessage } from '@/features/files/uploadRules'
import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'
import type { SensitiveMatch } from './workApi'

const REASONS = vi as { reason: { work: Record<string, string>; files: Record<string, string> } }

/**
 * What a refused Attachment or note says: `work`'s reason in its own words (a link, the Job's quota,
 * the author rule), a `files` reason for an upload, or else the Problem Details text.
 */
export function workRefusal(error: unknown): string {
  const reason = error instanceof ApiError ? error.problem.reason : undefined
  if (reason && reason in REASONS.reason.work) {
    return t(`reason.work.${reason}` as MessageKey)
  }
  if (reason && reason in REASONS.reason.files) {
    return uploadMessage(error)
  }
  return problemMessage(error, { module: 'work' }).text
}

/** The masked matches of an FR-47 refusal that "Che và lưu" can answer; none for anything else. */
export function sensitiveMatches(error: unknown): SensitiveMatch[] | undefined {
  if (error instanceof ApiError && error.problem.reason === 'SENSITIVE_CONTENT') {
    return (error.problem as { matches?: SensitiveMatch[] }).matches ?? []
  }
  return undefined
}
