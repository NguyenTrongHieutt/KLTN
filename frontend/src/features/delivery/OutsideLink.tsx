import { ExternalLink } from 'lucide-react'
import { t } from '@/i18n/t'

/**
 * A preview or recording link (AD-35, UX-DR30): `https` only, any other scheme stays plain text; it opens
 * in a new tab without the opener, shows its domain beside it, and N-11 under it.
 * @param warning whether N-11's line follows, which it does for every link a team wrote. The handover's own
 * GitHub links (FR-32, Story 23.8b) leave it out: vibefix built them, and GitHub is where the reader signs in
 */
export function OutsideLink({
  url,
  domain,
  label,
  warning = true,
}: {
  url: string
  domain: string
  label: string
  warning?: boolean
}) {
  const safe = /^https:\/\//i.test(url)
  return (
    <span className="flex flex-col gap-0.5">
      <span className="flex flex-wrap items-center gap-1.5">
        {safe ? (
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
          >
            {label}
            <ExternalLink aria-hidden="true" className="size-3.5" />
          </a>
        ) : (
          <span className="break-all">{url}</span>
        )}
        <span className="text-caption text-muted-foreground break-all">{`(${domain})`}</span>
      </span>
      {warning && (
        <span className="text-caption text-muted-foreground">{t('screen.handover.preview.warning', { domain })}</span>
      )}
    </span>
  )
}
