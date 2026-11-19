import { t, type MessageKey } from '@/i18n/t'
import type { DeliveryReport } from './deliveryApi'
import { OutsideLink } from './OutsideLink'

type HandoverLink = DeliveryReport['links'][number]

/**
 * A handover's links in the Mentor's order (D3, FR-10, FR-32, Story 24.3): each with its kind, its label as the
 * link's text, its domain and N-11, opening in a new tab without the opener (AD-21, AD-35). The domain comes from
 * the URL's text; vibefix never requested any of them (AD-32).
 */
export function HandoverLinkList({ links }: { links: HandoverLink[] }) {
  if (links.length === 0) {
    return null
  }
  return (
    <ul className="flex flex-col gap-2">
      {links.map((link, index) => (
        <li key={`${index}:${link.url}`} className="flex flex-col gap-0.5">
          <span className="text-caption font-semibold text-muted-foreground">
            {t(`screen.handover.links.kind${link.kind}` as MessageKey)}
          </span>
          <OutsideLink url={link.url} domain={link.domain} label={link.label} />
        </li>
      ))}
    </ul>
  )
}
