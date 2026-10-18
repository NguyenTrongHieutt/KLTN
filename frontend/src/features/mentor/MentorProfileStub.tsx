import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'

const STATUS: Record<string, { label: MessageKey; tone: string }> = {
  PENDING: { label: 'screen.mentor.profile.statusPending', tone: 'bg-warning-soft' },
  APPROVED: { label: 'screen.mentor.profile.statusApproved', tone: 'bg-success-soft' },
  REJECTED: { label: 'screen.mentor.profile.statusRejected', tone: 'bg-destructive-soft' },
  REVOKED: { label: 'screen.mentor.profile.statusRevoked', tone: 'bg-destructive-soft' },
}

/**
 * MEN-5 (`/ho-so`), the only screen of a Mentor that is not approved (FR-74); any other role finds
 * nothing here. The profile form and its review arrive with the Mentor approval story; this shows
 * where the account stands.
 */
export function MentorProfileStub() {
  const session = useSession()
  const account = session.data?.account
  if (account?.role !== 'MENTOR') {
    return <NotFoundScreen />
  }
  return <MentorProfile status={account.mentorApprovalStatus ?? 'PENDING'} />
}

function MentorProfile({ status }: { status: string }) {
  usePageTitle(t('screen.mentor.profile.title'))
  const shown = STATUS[status] ?? STATUS.PENDING

  return (
    <div className="mx-auto flex max-w-reading-max flex-col gap-4 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.mentor.profile.heading')}</PageHeading>
      <p className="text-muted-foreground">{t('screen.mentor.profile.lead')}</p>
      <p>
        <span className={cn('inline-flex rounded-full px-3 py-1 text-label', shown.tone)}>{t(shown.label)}</span>
      </p>
      <p className="rounded-lg border border-border bg-card p-6 text-body-sm">{t('screen.mentor.profile.next')}</p>
    </div>
  )
}
