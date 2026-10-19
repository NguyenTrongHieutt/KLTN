import { ExternalLink, TriangleAlert } from 'lucide-react'
import { t } from '@/i18n/t'
import { COMMIT_EMAIL_HELP } from './nda'

/** N-14 and the NDA text in a scrollable region, as the Mentor's and the Crew's NDA panels show it. */
export function NdaText() {
  return (
    <>
      <p className="text-body-sm">{t('screen.nda.n14')}</p>
      <div
        tabIndex={0}
        role="region"
        aria-label={t('screen.nda.textLabel')}
        className="max-h-48 overflow-auto rounded-md border border-border bg-background p-4 text-body-sm"
      >
        <p className="font-semibold">{t('screen.nda.article1')}</p>
        <p>{t('screen.nda.pending')}</p>
        <p className="mt-2 font-semibold">{t('screen.nda.article2')}</p>
        <p>{t('screen.nda.pending')}</p>
      </div>
    </>
  )
}

/** N-23 for a Code Job: commit metadata is public, with "Tôi đã hiểu" (asked once per notice version). */
export function CommitEmailNotice({ understood, onChange }: { understood: boolean; onChange: (understood: boolean) => void }) {
  return (
    <div role="note" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <div className="flex flex-col gap-1.5 text-body-sm">
        <p>{t('screen.nda.n23')}</p>
        <a
          href={COMMIT_EMAIL_HELP}
          target="_blank"
          rel="noopener noreferrer"
          className="flex w-fit items-center gap-1 text-primary underline-offset-4 hover:underline"
        >
          {t('screen.nda.n23Link')}
          <ExternalLink aria-hidden="true" className="size-4" />
        </a>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={understood} onChange={(event) => onChange(event.target.checked)} />
          {t('screen.nda.understood')}
        </label>
      </div>
    </div>
  )
}
