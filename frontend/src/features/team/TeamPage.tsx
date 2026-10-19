import { usePageTitle } from '@/app/usePageTitle'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { MentorTeamPage } from './MentorTeamPage'
import { StudentTeamPage } from './StudentTeamPage'

/**
 * `/nhom`: MEN-6 for an approved Mentor, STU-6 for a Student; anyone else finds nothing. A Mentor who
 * is not approved only reaches MEN-5 (FR-74); the revoked state's screens are FR-59's.
 */
export function TeamPage() {
  usePageTitle(t('screen.team.title'))
  const account = useSession().data?.account
  if (account?.role === 'MENTOR' && account.mentorApprovalStatus === 'APPROVED') {
    return <MentorTeamPage />
  }
  if (account?.role === 'STUDENT') {
    return <StudentTeamPage />
  }
  return <NotFoundScreen />
}
