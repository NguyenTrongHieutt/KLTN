import { UserRound } from 'lucide-react'
import { Navigate } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { ReadSessionAgain } from '@/features/job/FindWorkGate'
import { t } from '@/i18n/t'
import { GitHubGate } from './GitHubGate'
import { useSession } from './useSession'

/**
 * "Hồ sơ công khai" (`/ho-so/cong-khai`, UX-DR85): the avatar menu's way to the user's own public profile, which
 * is addressed by their GitHub username. With GitHub linked it opens that profile; without, its Empty state says
 * why and shows the GitHub gate, the one way forward.
 */
export function PublicProfilePage() {
  usePageTitle(t('screen.profile.public.title'))
  const login = useSession().data?.githubLogin
  if (login) {
    return <Navigate to={`/sv/${encodeURIComponent(login)}`} replace />
  }
  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.profile.public.heading')}</PageHeading>
      <div className="flex flex-col items-center gap-2.5 rounded-lg border border-border bg-card px-6 py-10 text-center">
        <UserRound aria-hidden="true" className="size-12 text-muted-foreground" />
        <p className="text-h3">{t('screen.profile.public.empty')}</p>
        <p className="text-body-sm text-muted-foreground">{t('screen.profile.public.emptyBody')}</p>
      </div>
      <GitHubGate
        action={t('screen.githubGate.action.publicProfile')}
        returnTo="/ho-so/cong-khai"
        keepsInput={false}
        onLeave={() => undefined}
      >
        <ReadSessionAgain />
      </GitHubGate>
    </div>
  )
}
