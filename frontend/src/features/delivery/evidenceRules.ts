/** What the form sends to let the Customer try the change (FR-32, AD-35). */
export interface Evidence {
  preview: string
  noPreview: boolean
  reason: string
  recording: string
  screenshotIds: string[]
}

export const NO_EVIDENCE: Evidence = { preview: '', noPreview: false, reason: '', recording: '', screenshotIds: [] }

export const MAX_SCREENSHOTS = 5

export const isHttps = (url: string) => /^https:\/\/\S+$/.test(url.trim())

/** Whether the evidence lets the handover go: a preview, or a reason with a recording, where required. */
export function evidenceReady(evidence: Evidence, required: boolean): boolean {
  if (evidence.screenshotIds.length > MAX_SCREENSHOTS) {
    return false
  }
  if (evidence.noPreview) {
    return evidence.reason.trim() !== '' && isHttps(evidence.recording)
  }
  return required ? isHttps(evidence.preview) : evidence.preview.trim() === '' || isHttps(evidence.preview)
}

/** The request fields for {@link Evidence}. */
export function evidenceBody(evidence: Evidence) {
  return evidence.noPreview
    ? { recordingUrl: evidence.recording.trim(), noPreviewReason: evidence.reason.trim(), screenshotIds: evidence.screenshotIds }
    : { previewUrl: evidence.preview.trim() || undefined, screenshotIds: evidence.screenshotIds }
}
