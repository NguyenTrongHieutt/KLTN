import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { verificationMessage } from '@/features/identity/verificationMessages'

/** The largest upload (AD-21): 10 MB, as the server counts it. */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

/** What one kind of upload accepts: the browser's types and, when it reports none, the extensions. */
export interface UploadRule {
  types: string[]
  extensions: RegExp
}

/** A student card (FR-5) or a Mentor's document (FR-74): an image the server can re-encode, or a PDF. */
export const IMAGE_OR_PDF: UploadRule = {
  types: ['image/jpeg', 'image/png', 'image/gif', 'image/bmp', 'application/pdf'],
  extensions: /\.(jpe?g|png|gif|bmp|pdf)$/i,
}

const FILES_REASONS = (vi as { reason: { files: Record<string, string> } }).reason.files

/**
 * The client's pre-check before an upload (UX-DR45): the `FILE_TOO_LARGE` or
 * `FILE_TYPE_NOT_ALLOWED` sentence, or nothing when the file may go. The server checks again by
 * the file's bytes.
 */
export function precheckUpload(file: File, rule: UploadRule): string | undefined {
  if (file.size > MAX_UPLOAD_BYTES) {
    return t('reason.files.FILE_TOO_LARGE')
  }
  const allowed = file.type ? rule.types.includes(file.type) : rule.extensions.test(file.name)
  return allowed ? undefined : t('reason.files.FILE_TYPE_NOT_ALLOWED')
}

/** What a refused upload says: a `files` reason in its own words, else as a verification refusal. */
export function uploadMessage(error: unknown): string {
  const reason = error instanceof ApiError ? error.problem.reason : undefined
  if (reason && reason in FILES_REASONS) {
    return t(`reason.files.${reason}` as MessageKey)
  }
  return verificationMessage(error)
}

/** What a Work Item Attachment may never be (NFR-3, AD-21): a page or a drawing that runs script. */
const ACTIVE_CONTENT = {
  types: ['image/svg+xml', 'text/html', 'application/xhtml+xml'],
  extensions: /\.(svg|svgz|html?|xhtml)$/i,
}

/**
 * The client's pre-check for a Work Item Attachment (FR-76, UX-DR45): at most 10 MB and never SVG or
 * HTML. Source files and logs have no reliable browser type, so everything else goes to the server,
 * which reads the bytes and accepts images, PDF, plain text, and archives.
 */
export function precheckAttachment(file: File): string | undefined {
  if (file.size > MAX_UPLOAD_BYTES) {
    return t('reason.files.FILE_TOO_LARGE')
  }
  const active = ACTIVE_CONTENT.types.includes(file.type) || ACTIVE_CONTENT.extensions.test(file.name)
  return active ? t('reason.files.FILE_TYPE_NOT_ALLOWED') : undefined
}
