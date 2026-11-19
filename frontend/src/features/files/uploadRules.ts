import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'

/** The largest upload (AD-21): 10 MB, as the server counts it. */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

const FILES_REASONS = (vi as { reason: { files: Record<string, string> } }).reason.files

/** What a refused upload says: a `files` reason in its own words, else the Problem Details text. */
export function uploadMessage(error: unknown): string {
  const reason = error instanceof ApiError ? error.problem.reason : undefined
  if (reason && reason in FILES_REASONS) {
    return t(`reason.files.${reason}` as MessageKey)
  }
  return problemMessage(error).text
}

