import { useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Toaster } from '@/components/Toaster'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { MentorProfileContent } from './MentorProfileContent'
import { usePublicMentor } from './teamApi'

/** PUB-5 (`/mentor/{username}`, FR-51): a Mentor's public profile, for anyone. */
export function MentorPublicPage() {
  const { username } = useParams()
  const mentor = usePublicMentor(username)
  const session = useSession()
  usePageTitle(mentor.data ? t('screen.team.profile.title', { name: mentor.data.displayName }) : t('screen.team.title'))
  if (mentor.isError && isNotFound(mentor.error)) {
    return <NotFoundScreen />
  }
  // The public layout has no toasts of its own; "Xin vào nhóm" confirms with one.
  return (
    <Toaster>
      <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
        <RegionState query={mentor} skeleton={<Skeleton className="h-96 w-full" />}>
          {mentor.data && (
            <MentorProfileContent mentor={mentor.data} visitor={session.data?.status !== 'SIGNED_IN'} onPage />
          )}
        </RegionState>
      </div>
    </Toaster>
  )
}
