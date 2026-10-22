import { ExternalLink } from 'lucide-react'
import { t } from '@/i18n/t'

/**
 * A preview or recording link (AD-35, UX-DR30): `https` only, any other scheme stays plain text; it opens
 * in a new tab without the opener, shows its domain beside it, and N-11 under it.
 */
export function OutsideLink({ url, domain, label }: { url: string; domain: string; label: string }) {
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
      <span className="text-caption text-muted-foreground">{t('screen.handover.preview.warning', { domain })}</span>
    </span>
  )
}
