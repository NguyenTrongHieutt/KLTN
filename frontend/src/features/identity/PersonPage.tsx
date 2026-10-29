import { Navigate, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { PersonProfileContent } from './PersonProfile'
import { usePublicPerson } from './personApi'
import { personPath } from './personText'

/**
 * PUB-4 (`/nguoi-dung/{username}`, FR-51, UX-DR96, Story 21.3): one public profile per person, for anyone, in the
 * public layout. The username is the GitHub username of a linked account; none, or two, is not found.
 */
export function PersonPage() {
  const { username } = useParams()
  const person = usePublicPerson(username)
  const name = person.data && !person.data.locked ? (person.data.displayName ?? `@${username ?? ''}`) : undefined
  usePageTitle(name ? t('screen.person.title', { name }) : t('screen.person.titleFallback'))
  if (!username || (person.isError && isNotFound(person.error))) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[860px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PersonProfileContent username={username} onPage />
    </div>
  )
}

/** PUB-5's `/mentor/{username}` and the Student page's `/sv/{username}` were merged into PUB-4 (UX-DR96). */
export function RedirectToPerson() {
  const { username } = useParams()
  return <Navigate to={username ? personPath(username) : '/'} replace />
}
