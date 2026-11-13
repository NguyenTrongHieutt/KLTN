import { ExternalLink, FolderGit2 } from 'lucide-react'
import { useEffect } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { t } from '@/i18n/t'
import { redirectTo } from '@/lib/redirect'
import { useSetup } from './setupApi'

/**
 * Where the Customer's read invitation to-do and notification lead (R5, UX-DR118, Story 23.7):
 * `/cong-viec/{jobId}/kho-trung-gian`. To-dos and notifications link only inside vibefix, so this page sends the
 * Customer on to their invitation to the Intermediary Repository on GitHub, or to the repository once they accepted,
 * from their own row of the Setup, whose link the server builds. With no live read of theirs it says so.
 */
export function IntermediaryInvitationPage() {
  usePageTitle(t('screen.job.intermediary.title'))
  const { jobId } = useParams()
  const setup = useSetup(jobId)
  const url = setup.data?.people.find((person) => person.role === 'CUSTOMER' && person.githubUrl)?.githubUrl
  useEffect(() => {
    if (url) {
      redirectTo(url)
    }
  }, [url])
  const settled = setup.isError || (setup.data !== undefined && !url)
  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.job.intermediary.title')}</PageHeading>
      <div className="flex flex-col items-center gap-2.5 rounded-lg border border-border bg-card px-6 py-10 text-center">
        <FolderGit2 aria-hidden="true" className="size-12 text-muted-foreground" />
        {url && (
          <>
            <p className="text-body-sm">{t('screen.job.intermediary.opening')}</p>
            <a
              href={url}
              className="inline-flex items-center gap-1 text-body-sm text-primary underline-offset-4 hover:underline"
            >
              {t('screen.job.intermediary.open')}
              <ExternalLink aria-hidden="true" className="size-3.5" />
            </a>
          </>
        )}
        {settled && (
          <>
            <p className="text-body-sm">{t('screen.job.intermediary.none')}</p>
            <Link
              to={`/cong-viec/${jobId ?? ''}`}
              className="text-body-sm text-primary underline-offset-4 hover:underline"
            >
              {t('screen.job.intermediary.back')}
            </Link>
          </>
        )}
      </div>
    </div>
  )
}
