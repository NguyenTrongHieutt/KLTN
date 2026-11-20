import { Fragment } from 'react'

/** What a sentence puts right after a link: it stays outside the link, as text. */
const TRAILING = '.,;:!?)]}\'"'

/**
 * An `https://` URL that starts a word, so the `https` part inside an `http:` URL or glued to a word stays text; it
 * runs to the next space or angle bracket.
 */
const HTTPS_URL = /(?<![\p{L}\p{N}_/=?&%.@+~#-])https:\/\/[^\s<>]+/giu

type Part = { text: string } | { url: string; host: string }

/** The ASCII host the browser would open, or nothing when the text is no `https` URL. */
function hostOf(url: string): string | undefined {
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' && parsed.hostname !== '' ? parsed.hostname : undefined
  } catch {
    return undefined
  }
}

function partsOf(text: string): Part[] {
  const parts: Part[] = []
  let shown = 0
  for (const match of text.matchAll(HTTPS_URL)) {
    let url = match[0]
    while (url !== '' && TRAILING.includes(url.at(-1) ?? '')) {
      url = url.slice(0, -1)
    }
    const host = hostOf(url)
    if (!host) {
      continue
    }
    if (match.index > shown) {
      parts.push({ text: text.slice(shown, match.index) })
    }
    parts.push({ url, host })
    shown = match.index + url.length
  }
  if (shown < text.length) {
    parts.push({ text: text.slice(shown) })
  }
  return parts
}

/**
 * A chat message's body wherever one is shown (FR-46, FR-77, Story 24.4a): text as text, never as markup, and each
 * `https://` URL a link with the URL as its text, opening in a new tab without the opener, followed by its domain,
 * the ASCII host the browser would open, so a look-alike name cannot hide where it goes. Punctuation that ends a
 * sentence stays outside the link, and `http:` or any other scheme stays text. Nothing is fetched. The caller's
 * element keeps the line breaks (`whitespace-pre-line` or `whitespace-pre-wrap`).
 */
export function MessageText({ text }: { text: string }) {
  return (
    <>
      {partsOf(text).map((part, index) =>
        'url' in part ? (
          <Fragment key={index}>
            <a
              href={part.url}
              target="_blank"
              rel="noopener noreferrer"
              className="break-all text-primary underline underline-offset-4"
            >
              {part.url}
            </a>{' '}
            <span className="text-muted-foreground">{`(${part.host})`}</span>
          </Fragment>
        ) : (
          <Fragment key={index}>{part.text}</Fragment>
        ),
      )}
    </>
  )
}
