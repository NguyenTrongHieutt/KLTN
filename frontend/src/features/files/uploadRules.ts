import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { problemMessage, reasonMessage, type ProblemContext } from '@/lib/problemMessage'

/** The largest upload (AD-21): 10 MB, as the server counts it. */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

const FILES_REASONS = (vi as { reason: { files: Record<string, string> } }).reason.files

/**
 * What a refused upload says: a `files` reason in its own words, then, when the screen names its module in
 * `context`, that module's reason for a `VALIDATION` answer, which the shared text would only count (the handover
 * reads `delivery`'s, Story 24.3c), else the Problem Details text in that context.
 */
export function uploadMessage(error: unknown, context?: ProblemContext): string {
  const reason = error instanceof ApiError ? error.problem.reason : undefined
  if (reason && reason in FILES_REASONS) {
    return t(`reason.files.${reason}` as MessageKey)
  }
  const own =
    error instanceof ApiError && error.status < 500 && error.problem.code === 'VALIDATION' && reason && context?.module
      ? reasonMessage(context.module, reason)
      : undefined
  return own ?? problemMessage(error, context).text
}
