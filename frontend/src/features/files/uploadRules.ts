import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { problemMessage, reasonMessage, type ProblemContext } from '@/lib/problemMessage'

/** The largest upload (AD-21): 10 MB, as the server counts it. */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

const FILES_REASONS = (vi as { reason: { files: Record<string, string> } }).reason.files

/**
 * What a refused upload says: the `files` reason's text; else, for a `VALIDATION` answer when `context` names a
 * module, that module's reason (the handover names `delivery`, Story 24.3c); else `problemMessage(error, context)`.
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
