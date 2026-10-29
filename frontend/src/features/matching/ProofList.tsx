import { FileText } from 'lucide-react'
import { OutsideLink } from '@/features/delivery/OutsideLink'
import { t } from '@/i18n/t'
import { formatSize } from '@/lib/fileSize'
import type { BidProof } from './applicationApi'

/**
 * A bid's proof items in their order (FR-13, AD-20, AD-21): each caption, then a file's download (an attachment,
 * never shown as a page) or a link as an External link, with its domain and N-11's caution line under it. Only the
 * bidder and the Job's Customer ever receive them.
 */
export function ProofList({ proof }: { proof: BidProof[] }) {
  if (proof.length === 0) {
    return <p className="text-body-sm text-muted-foreground">{t('screen.matching.proof.none')}</p>
  }
  return (
    <ul className="flex flex-col gap-2">
      {proof.map((item) => (
        <li key={item.position} className="flex flex-col gap-1 rounded-md border border-border px-4 py-2.5 text-body-sm">
          <span className="break-words">{item.caption}</span>
          {item.kind === 'FILE' && item.fileUrl ? (
            <a
              href={item.fileUrl}
              download
              className="flex items-center gap-2 break-all text-primary underline-offset-4 hover:underline"
            >
              <FileText aria-hidden="true" className="size-4 shrink-0" />
              {t('screen.matching.proof.file', { name: item.fileName ?? '', size: formatSize(item.sizeBytes ?? 0) })}
            </a>
          ) : (
            item.url && <OutsideLink url={item.url} domain={item.domain ?? ''} label={item.url} />
          )}
        </li>
      ))}
    </ul>
  )
}
