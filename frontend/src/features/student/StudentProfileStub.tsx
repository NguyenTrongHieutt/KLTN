import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { t } from '@/i18n/t'

/**
 * STU-5 (`/ho-so`) for a Student, where `identity.verify_student` leads. The profile arrives with
 * Story 1.8 and verification with Stories 1.9 and 1.13; this says what will be here.
 */
export function StudentProfileStub() {
  usePageTitle(t('screen.student.profile.title'))

  return (
    <div className="mx-auto flex max-w-reading-max flex-col gap-4 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.student.profile.heading')}</PageHeading>
      <p className="text-muted-foreground">{t('screen.student.profile.lead')}</p>
      <p className="rounded-lg border border-border bg-card p-6 text-body-sm">{t('screen.student.profile.next')}</p>
    </div>
  )
}
