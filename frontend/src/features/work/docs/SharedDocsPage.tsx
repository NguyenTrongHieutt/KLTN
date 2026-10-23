import { FileText } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { SafeMarkdown } from '@/components/SafeMarkdown'
import { Skeleton } from '@/components/Skeleton'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { usePage } from './docsApi'
import { useSharedPages, type SharedJob } from './sharedPagesApi'

/**
 * "Tài liệu từ công việc trước" (`/cong-viec/{jobId}/tai-lieu-cu/{pageId?}`, FR-70, UX-DR59): the earlier Jobs
 * the Customer shared with this Job, each with its customer-visible Pages, and the chosen Page read-only,
 * without history. Only the Job's Mentor and active Crew get here; everyone else sees the not-found screen.
 */
export function SharedDocsPage() {
  const { jobId, pageId } = useParams()
  const role = useSession().data?.account?.role
  if (!jobId || (role !== undefined && role !== 'MENTOR' && role !== 'STUDENT')) {
    return <NotFoundScreen />
  }
  return <SharedDocs jobId={jobId} pageId={pageId} back={role === 'MENTOR' ? `/cong-viec/${jobId}` : '/cong-viec'} />
}

function SharedDocs({ jobId, pageId, back }: { jobId: string; pageId: string | undefined; back: string }) {
  const shared = useSharedPages(jobId)
  usePageTitle(t('screen.sharedDocs.title'))
  if (shared.isError && isNotFound(shared.error)) {
    return <NotFoundScreen />
  }
  const shown = pageId ?? shared.data?.find((job) => job.pages.length > 0)?.pages[0]?.id
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-1">
        <Link to={back} className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline">
          {t('screen.sharedDocs.back')}
        </Link>
        <PageHeading>{t('screen.sharedDocs.heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.sharedDocs.note')}</p>
      </div>
      <RegionState query={shared} skeleton={<Skeleton className="h-96 w-full" />}>
        {shared.data &&
          (shared.data.length === 0 ? (
            <p className="text-body-sm text-muted-foreground">{t('screen.sharedDocs.empty')}</p>
          ) : (
            <div className="grid gap-5 md:grid-cols-[260px_minmax(0,1fr)]">
              <nav aria-label={t('screen.sharedDocs.heading')} className="flex flex-col gap-4">
                {shared.data.map((job) => (
                  <JobPages key={job.jobId} job={job} jobId={jobId} shown={shown} />
                ))}
              </nav>
              <main className="min-w-0">{shown && <SharedPage key={shown} pageId={shown} />}</main>
            </div>
          ))}
      </RegionState>
    </div>
  )
}

function JobPages({ job, jobId, shown }: { job: SharedJob; jobId: string; shown: string | undefined }) {
  return (
    <section className="flex flex-col gap-1">
      <h2 className="text-label font-semibold break-words">{job.title}</h2>
      {job.pages.length === 0 ? (
        <p className="text-caption text-muted-foreground">{t('screen.sharedDocs.noPages')}</p>
      ) : (
        <ul className="flex flex-col gap-0.5">
          {job.pages.map((page) => (
            <li key={page.id} className={cn(page.parentId && 'pl-5')}>
              <Link
                to={`/cong-viec/${jobId}/tai-lieu-cu/${page.id}`}
                aria-current={page.id === shown ? 'page' : undefined}
                className={cn(
                  'flex min-h-tap-min min-w-0 items-center gap-1.5 rounded-md px-2 text-body-sm no-underline',
                  page.id === shown ? 'bg-primary-soft font-semibold text-primary' : 'text-foreground hover:bg-muted',
                )}
              >
                <FileText aria-hidden="true" className="size-4 shrink-0" />
                <span className="truncate">{page.title}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function SharedPage({ pageId }: { pageId: string }) {
  const page = usePage(pageId)
  if (page.isError && isNotFound(page.error)) {
    return <p className="text-body-sm text-muted-foreground">{t('screen.docs.notFound')}</p>
  }
  return (
    <RegionState query={page} skeleton={<Skeleton className="h-64 w-full" />}>
      {page.data && (
        <article className="flex flex-col gap-4">
          <div className="flex min-w-0 flex-col gap-1">
            <h2 className="text-h2 break-words">{page.data.title}</h2>
            <p className="text-caption text-muted-foreground">
              {t('screen.docs.versionLine', {
                n: page.data.versionNo,
                author: page.data.author || t('screen.docs.unknownAuthor'),
                time: formatDayMonthTime(page.data.at),
              })}
            </p>
          </div>
          {page.data.body.trim() === '' ? (
            <p className="text-body-sm text-muted-foreground">{t('screen.docs.emptyBody')}</p>
          ) : (
            <SafeMarkdown>{page.data.body}</SafeMarkdown>
          )}
        </article>
      )}
    </RegionState>
  )
}

/** The way in from JOB-3 and the Mentor's JOB-6, shown only when something is shared with this Job. */
export function SharedDocsLink({ jobId }: { jobId: string }) {
  const shared = useSharedPages(jobId)
  if (!shared.data || shared.data.length === 0) {
    return null
  }
  return (
    <Link
      to={`/cong-viec/${jobId}/tai-lieu-cu`}
      className="flex w-fit items-center gap-1.5 text-body-sm text-primary underline-offset-4 hover:underline"
    >
      <FileText aria-hidden="true" className="size-4 shrink-0" />
      {t('screen.sharedDocs.link', { n: shared.data.length })}
    </Link>
  )
}
