import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { PageHeading } from '@/app/PageHeading'
import { Skeleton } from '@/components/Skeleton'
import { SESSION_QUERY_KEY } from '@/features/identity/api'
import { GitHubGate } from '@/features/identity/GitHubGate'
import { t } from '@/i18n/t'

/**
 * "Tìm việc" (X1) behind the GitHub gate (UX-DR95, FR-80, Story 20.1): the session's capabilities say GitHub is
 * not linked, so MEN-2 and MEN-3 show the gate in place of the Job Posts, with one way forward. The API
 * refuses the reads too (`GITHUB_NOT_LINKED`). Should the link read find GitHub linked after all, the session
 * is read again and the page shows the Job Posts.
 */
export function FindWorkGate({ returnTo }: { returnTo: string }) {
  return (
    <div className="mx-auto flex max-w-[1000px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.jobPost.heading')}</PageHeading>
      <GitHubGate
        action={t('screen.githubGate.action.findWork')}
        returnTo={returnTo}
        keepsInput={false}
        onLeave={() => undefined}
      >
        <ReadSessionAgain />
      </GitHubGate>
    </div>
  )
}

/** The capabilities were read before GitHub was linked: the session is read again. */
function ReadSessionAgain() {
  const queryClient = useQueryClient()
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY })
  }, [queryClient])
  return <Skeleton className="h-48 w-full" />
}
