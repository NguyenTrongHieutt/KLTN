import { usePageTitle } from '@/app/usePageTitle'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { MentorTeamPage } from './MentorTeamPage'
import { StudentTeamPage } from './StudentTeamPage'

/**
 * `/nhom`: MEN-6 for a Mentor, STU-6 for a Student; anyone else finds nothing. Teams are retired: the Team
 * endpoints refuse every caller (Story 20.3) and the session names no role since Story 20.6, so every user finds
 * nothing here; Story 21.7 deletes the page with its route.
 */
export function TeamPage() {
  usePageTitle(t('screen.team.title'))
  const role = (useSession().data?.account as { role?: string } | undefined)?.role
  if (role === 'MENTOR') {
    return <MentorTeamPage />
  }
  if (role === 'STUDENT') {
    return <StudentTeamPage />
  }
  return <NotFoundScreen />
}
