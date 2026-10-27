import { usePageTitle } from '@/app/usePageTitle'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { MentorTeamPage } from './MentorTeamPage'
import { StudentTeamPage } from './StudentTeamPage'

/**
 * `/nhom`: MEN-6 for a Mentor, STU-6 for a Student; anyone else finds nothing. No Mentor needs an
 * approval (Story 19.7).
 */
export function TeamPage() {
  usePageTitle(t('screen.team.title'))
  const account = useSession().data?.account
  if (account?.role === 'MENTOR') {
    return <MentorTeamPage />
  }
  if (account?.role === 'STUDENT') {
    return <StudentTeamPage />
  }
  return <NotFoundScreen />
}
