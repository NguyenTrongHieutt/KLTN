/** A link's kind (D3, FR-32): "Bản ghi / video" or "Tài liệu". */
export type LinkKind = 'VIDEO' | 'DOCUMENT'

/**
 * One row of the Handover evidence list's links (Story 24.3): a recording or a document the Mentor's own tools
 * host, with a short label. `id` keys the row on the form only and is never sent.
 */
export interface EvidenceLink {
  id: string
  kind: LinkKind
  label: string
  url: string
}

/** What the form sends to let the Customer try the change and read the work (FR-32, AD-35). */
export interface Evidence {
  preview: string
  noPreview: boolean
  reason: string
  links: EvidenceLink[]
  screenshotIds: string[]
}

export const NO_EVIDENCE: Evidence = { preview: '', noPreview: false, reason: '', links: [], screenshotIds: [] }

export const MAX_SCREENSHOTS = 5

export const MAX_LINKS = 10

export const MAX_LINK_LABEL = 80

export const MAX_LINK_URL = 500

export const isHttps = (url: string) => /^https:\/\/\S+$/.test(url.trim())

/** A new, empty link row of `kind`. */
export function newLink(kind: LinkKind): EvidenceLink {
  return { id: crypto.randomUUID(), kind, label: '', url: '' }
}

/**
 * The domain of an `https` URL, read from its text alone (AD-32: nothing is requested to name it), as the
 * report shows it beside each link; `undefined` until the URL is `https`.
 */
export function domainOf(url: string): string | undefined {
  const text = url.trim()
  if (!isHttps(text)) {
    return undefined
  }
  const authority = text.slice('https://'.length).split(/[/?#]/)[0]
  const host = authority.slice(authority.lastIndexOf('@') + 1).replace(/:\d*$/, '')
  return host === '' ? undefined : host.toLowerCase()
}

/** Whether a link row is complete: a label of 1 to 80 characters and an `https` URL of at most 500. */
export function linkReady(link: EvidenceLink): boolean {
  const label = link.label.trim()
  return label !== '' && label.length <= MAX_LINK_LABEL && isHttps(link.url) && link.url.trim().length <= MAX_LINK_URL
}

/**
 * Whether the evidence lets the handover go (FR-10, FR-32): every link row complete and at most ten of them; an
 * Assessment Job's report as a "Tài liệu" link; otherwise a preview, or a reason with a "Bản ghi / video" link,
 * where required.
 */
export function evidenceReady(evidence: Evidence, required: boolean, assessment: boolean): boolean {
  if (evidence.screenshotIds.length > MAX_SCREENSHOTS || evidence.links.length > MAX_LINKS) {
    return false
  }
  if (!evidence.links.every(linkReady)) {
    return false
  }
  if (assessment) {
    return evidence.links.some((link) => link.kind === 'DOCUMENT')
  }
  if (evidence.noPreview) {
    return evidence.reason.trim() !== '' && evidence.links.some((link) => link.kind === 'VIDEO')
  }
  return required ? isHttps(evidence.preview) : evidence.preview.trim() === '' || isHttps(evidence.preview)
}

/** The request fields for {@link Evidence}: an Assessment Job's form has no preview part, so it sends none. */
export function evidenceBody(evidence: Evidence, assessment: boolean) {
  const links = evidence.links.map((link) => ({ kind: link.kind, label: link.label.trim(), url: link.url.trim() }))
  if (assessment) {
    return { links, screenshotIds: evidence.screenshotIds }
  }
  return evidence.noPreview
    ? { noPreviewReason: evidence.reason.trim(), links, screenshotIds: evidence.screenshotIds }
    : { previewUrl: evidence.preview.trim() || undefined, links, screenshotIds: evidence.screenshotIds }
}
