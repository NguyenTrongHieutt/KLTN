import { Info } from 'lucide-react'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { TermHint, TermScope } from '@/components/TermHint'
import { rich } from '@/i18n/rich'
import { t } from '@/i18n/t'

const COLLECTED = ['identity', 'profile', 'student', 'mentor', 'jobs'] as const

/**
 * PUB-7: the current personal-data notice with its version number (NFR-9, N-10). The text is the
 * draft awaiting the legal wording (OQ5); acknowledgments record `notice.privacy.version`.
 */
export function PrivacyPage() {
  usePageTitle(t('screen.public.privacy.title'))

  return (
    <TermScope>
      <article className="mx-auto flex max-w-reading-max flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
        <div className="flex flex-col gap-2">
          <PageHeading>{t('screen.public.privacy.heading')}</PageHeading>
          <p className="text-body-sm text-muted-foreground">{t('screen.public.privacy.lead')}</p>
        </div>
        <div className="flex flex-col gap-1">
          <p className="text-label font-semibold">
            {t('notice.privacy.versionLine', { version: t('notice.privacy.version') })}
          </p>
          <p className="text-caption text-muted-foreground">{t('notice.privacy.effective')}</p>
        </div>
        <section className="flex flex-col gap-2">
          <h2 className="text-h2">{t('notice.privacy.collectedHeading')}</h2>
          <ul className="flex list-disc flex-col gap-1 ps-6 text-body-sm">
            {COLLECTED.map((item) => (
              <li key={item}>{rich(`notice.privacy.collected.${item}`, { github: <TermHint term="github" /> })}</li>
            ))}
          </ul>
        </section>
        <section className="flex flex-col gap-2">
          <h2 className="text-h2">{t('notice.privacy.purposeHeading')}</h2>
          <p className="text-body-sm">{t('notice.privacy.purpose')}</p>
        </section>
        <section className="flex flex-col gap-2">
          <h2 className="text-h2">{t('notice.privacy.retentionHeading')}</h2>
          <p className="text-body-sm">{t('notice.privacy.retention')}</p>
        </section>
        <section className="flex flex-col gap-2">
          <h2 className="text-h2">{t('notice.privacy.rightsHeading')}</h2>
          <p className="text-body-sm">{t('notice.privacy.rights')}</p>
        </section>
        <div className="flex gap-3 rounded-md border-l-4 border-info bg-info-soft p-4">
          <Info aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-info" />
          <p className="text-body-sm">{t('notice.privacy.changes')}</p>
        </div>
      </article>
    </TermScope>
  )
}
