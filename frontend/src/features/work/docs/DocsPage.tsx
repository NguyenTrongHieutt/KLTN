import { Dialog } from '@base-ui/react/dialog'
import { FileText, History, Plus } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link, Navigate, useBlocker, useNavigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { SafeMarkdown } from '@/components/SafeMarkdown'
import { Sheet } from '@/components/Sheet'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/identity/useSession'
import { JobHeader } from '@/features/job/JobHeader'
import { mentorMeta } from '@/features/job/jobMeta'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob } from '@/features/matching/mentorJobApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { hasBoard } from '@/features/work/workApi'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  usePage,
  usePageTree,
  usePageVersion,
  usePageVersions,
  useRestorePage,
  useSavePage,
  type PageDoc,
  type PageTree,
} from './docsApi'

/**
 * JOB-3 "Tài liệu" (`/cong-viec/{jobId}/tai-lieu/{pageId?}`, FR-29, FR-47, AD-23, UX-DR39): the Page tree
 * (two levels), the Page in the safe Markdown view, the editor with "Soạn" / "Xem trước" and an explicit
 * "Lưu" (each save a version), and "Lịch sử phiên bản" with "Xem" and "Khôi phục". Leaving with unsaved
 * changes asks first; a stale save opens the Stale dialog. The Mentor and the active Crew write; a former
 * member reads; anyone else sees the not-found screen. Templates and visibility come with Story 5.6.
 */
export function DocsPage() {
  const { jobId, pageId } = useParams()
  const account = useSession().data?.account
  if (!jobId || (account !== undefined && account.role !== 'MENTOR' && account.role !== 'STUDENT')) {
    return <NotFoundScreen />
  }
  return account?.role === 'MENTOR' ? (
    <MentorDocs jobId={jobId} pageId={pageId} />
  ) : (
    <StudentDocs jobId={jobId} pageId={pageId} />
  )
}

function MentorDocs({ jobId, pageId }: { jobId: string; pageId: string | undefined }) {
  const job = useMentorJob(jobId)
  const tree = usePageTree(job.data && hasBoard(job.data.state) ? jobId : undefined)
  usePageTitle(job.data ? t('screen.docs.title', { job: job.data.title }) : t('screen.job.title'))
  if ((job.isError && isNotFound(job.error)) || (tree.isError && isNotFound(tree.error))) {
    return <NotFoundScreen />
  }
  if (job.data && !hasBoard(job.data.state)) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <Frame>
      <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
        {job.data && (
          <JobHeader title={job.data.title} state={job.data.state} meta={mentorMeta(job.data)} audience="mentor">
            <MentorJobTabs jobId={job.data.id} state={job.data.state} current="docs" payment={job.data.agreementConfirmed} />
          </JobHeader>
        )}
      </RegionState>
      {job.data && (
        <RegionState query={tree} skeleton={<Skeleton className="h-96 w-full" />}>
          {tree.data && <Space tree={tree.data} jobId={jobId} pageId={pageId} />}
        </RegionState>
      )}
    </Frame>
  )
}

function StudentDocs({ jobId, pageId }: { jobId: string; pageId: string | undefined }) {
  const tree = usePageTree(jobId)
  usePageTitle(tree.data ? t('screen.docs.title', { job: tree.data.title }) : t('screen.docs.tab'))
  if (tree.isError && isNotFound(tree.error)) {
    return <NotFoundScreen />
  }
  return (
    <Frame>
      <div className="flex flex-col gap-1">
        <Link to="/cong-viec" className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline">
          {t('screen.crewChat.back')}
        </Link>
        <PageHeading>{tree.data?.title ?? t('screen.docs.tab')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.docs.tab')}</p>
      </div>
      <RegionState query={tree} skeleton={<Skeleton className="h-96 w-full" />}>
        {tree.data && <Space tree={tree.data} jobId={jobId} pageId={pageId} />}
      </RegionState>
    </Frame>
  )
}

function Space({ tree, jobId, pageId }: { tree: PageTree; jobId: string; pageId: string | undefined }) {
  const [creating, setCreating] = useState<{ parentId?: string }>()
  const shown = pageId ?? tree.pages[0]?.id
  const base = `/cong-viec/${jobId}/tai-lieu`
  return (
    <div className="grid gap-5 md:grid-cols-[260px_minmax(0,1fr)]">
      <nav aria-label={t('screen.docs.tree')} className="flex flex-col gap-2">
        {!tree.readOnly && (
          <Button type="button" size="compact" className="self-start" onClick={() => setCreating({})}>
            <Plus aria-hidden="true" />
            {t('screen.docs.newPage')}
          </Button>
        )}
        {tree.pages.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.docs.empty')}</p>
        ) : (
          <ul className="flex flex-col gap-0.5">
            {tree.pages.map((page) => (
              <li key={page.id} className={cn('flex items-center justify-between gap-1', page.parentId && 'pl-5')}>
                <Link
                  to={`${base}/${page.id}`}
                  aria-current={page.id === shown ? 'page' : undefined}
                  className={cn(
                    'flex min-h-tap-min min-w-0 items-center gap-1.5 rounded-md px-2 text-body-sm no-underline',
                    page.id === shown ? 'bg-primary-soft font-semibold text-primary' : 'text-foreground hover:bg-muted',
                  )}
                >
                  <FileText aria-hidden="true" className="size-4 shrink-0" />
                  <span className="truncate">{page.title}</span>
                </Link>
                {!tree.readOnly && !page.parentId && (
                  <button
                    type="button"
                    aria-label={t('screen.docs.newChild', { title: page.title })}
                    onClick={() => setCreating({ parentId: page.id })}
                    className="rounded p-1 text-muted-foreground hover:bg-muted"
                  >
                    <Plus aria-hidden="true" className="size-4" />
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </nav>
      <main className="min-w-0">
        {creating ? (
          <Editor
            key={`new-${creating.parentId ?? ''}`}
            tree={tree}
            jobId={jobId}
            parentId={creating.parentId}
            onDone={() => setCreating(undefined)}
          />
        ) : (
          shown && <PageArea key={shown} tree={tree} jobId={jobId} pageId={shown} />
        )}
      </main>
    </div>
  )
}

function PageArea({ tree, jobId, pageId }: { tree: PageTree; jobId: string; pageId: string }) {
  const page = usePage(pageId)
  const [editing, setEditing] = useState(false)
  const [history, setHistory] = useState(false)
  if (page.isError && isNotFound(page.error)) {
    return <p className="text-body-sm text-muted-foreground">{t('screen.docs.notFound')}</p>
  }
  return (
    <RegionState query={page} skeleton={<Skeleton className="h-64 w-full" />}>
      {page.data &&
        (editing ? (
          <Editor
            key={`${page.data.id}-${page.data.versionNo}`}
            tree={tree}
            jobId={jobId}
            page={page.data}
            parentId={page.data.parentId}
            onDone={() => setEditing(false)}
            onStale={() => {
              setEditing(false)
              void page.refetch()
            }}
          />
        ) : (
          <article className="flex flex-col gap-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
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
              <div className="flex flex-wrap gap-2">
                <Button type="button" variant="secondary" size="compact" onClick={() => setHistory(true)}>
                  <History aria-hidden="true" />
                  {t('screen.docs.history')}
                </Button>
                {!page.data.readOnly && (
                  <Button type="button" size="compact" onClick={() => setEditing(true)}>
                    {t('screen.docs.edit')}
                  </Button>
                )}
              </div>
            </div>
            {page.data.body.trim() === '' ? (
              <p className="text-body-sm text-muted-foreground">{t('screen.docs.emptyBody')}</p>
            ) : (
              <SafeMarkdown>{page.data.body}</SafeMarkdown>
            )}
            <VersionHistory jobId={jobId} page={page.data} open={history} onClose={() => setHistory(false)} />
          </article>
        ))}
    </RegionState>
  )
}

interface Match {
  masked: string
}

/** The Page editor (UX-DR39): "Soạn" / "Xem trước", an explicit "Lưu", the guard on leaving with unsaved changes. */
function Editor({
  tree,
  jobId,
  page,
  parentId,
  onDone,
  onStale,
}: {
  tree: PageTree
  jobId: string
  page?: PageDoc
  parentId?: string
  onDone: () => void
  onStale?: () => void
}) {
  const ids = useId()
  const toasts = useToasts()
  const navigate = useNavigate()
  const save = useSavePage(jobId, page?.id)
  const [title, setTitle] = useState(page?.title ?? '')
  const [body, setBody] = useState(page?.body ?? '')
  const [parent, setParent] = useState(parentId ?? '')
  const [tab, setTab] = useState<'write' | 'preview'>('write')
  const [error, setError] = useState<string>()
  const [matches, setMatches] = useState<Match[]>()
  const [stale, setStale] = useState(false)
  const dirty = title !== (page?.title ?? '') || body !== (page?.body ?? '') || parent !== (parentId ?? '')
  // Read when a navigation starts, so the move right after a save is not held back.
  const guard = useRef(false)
  useEffect(() => {
    guard.current = dirty
  }, [dirty])
  const blocker = useBlocker(() => guard.current)
  useEffect(() => {
    if (!dirty) {
      return
    }
    const warn = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  const parents = tree.pages.filter((other) => !other.parentId && other.id !== page?.id)
  const hasChildren = page ? tree.pages.some((other) => other.parentId === page.id) : false

  const submit = (sensitive: 'CHECK' | 'MASK') => {
    if (title.trim() === '') {
      setError(t('screen.docs.titleRequired'))
      return
    }
    save.mutate(
      { title: title.trim(), body, parentId: parent === '' ? undefined : parent, version: page?.versionNo, sensitive },
      {
        onSuccess: (result) => {
          guard.current = false
          setMatches(undefined)
          toasts.success(t('screen.docs.saved', { n: result.versionNo }))
          onDone()
          if (!page) {
            void navigate(`/cong-viec/${jobId}/tai-lieu/${result.id}`)
          }
        },
        onError: (failure) => {
          if (failure instanceof ApiError && failure.problem.code === 'STALE') {
            setStale(true)
            return
          }
          if (failure instanceof ApiError && failure.problem.reason === 'SENSITIVE_CONTENT') {
            setMatches((failure.problem as { matches?: Match[] }).matches ?? [])
            return
          }
          setError(problemMessage(failure, { module: 'work' }).text)
        },
      },
    )
  }

  return (
    <section aria-label={t(page ? 'screen.docs.editing' : 'screen.docs.newPage')} className="flex flex-col gap-3">
      <label htmlFor={`${ids}-title`} className="text-label">
        {t('screen.docs.pageTitle')}
      </label>
      <input
        id={`${ids}-title`}
        value={title}
        maxLength={200}
        onChange={(event) => setTitle(event.target.value)}
        className={inputClass}
      />
      {!hasChildren && parents.length > 0 && (
        <>
          <label htmlFor={`${ids}-parent`} className="text-label">
            {t('screen.docs.parent')}
          </label>
          <select id={`${ids}-parent`} value={parent} onChange={(event) => setParent(event.target.value)} className={inputClass}>
            <option value="">{t('screen.docs.topLevel')}</option>
            {parents.map((other) => (
              <option key={other.id} value={other.id}>
                {other.title}
              </option>
            ))}
          </select>
        </>
      )}
      <div role="tablist" aria-label={t('screen.docs.mode')} className="flex gap-1 border-b border-border">
        {(['write', 'preview'] as const).map((mode) => (
          <button
            key={mode}
            type="button"
            role="tab"
            aria-selected={tab === mode}
            onClick={() => setTab(mode)}
            className={cn(
              '-mb-px border-b-2 px-3 pb-2 text-label',
              tab === mode ? 'border-primary font-semibold text-primary' : 'border-transparent text-muted-foreground',
            )}
          >
            {t(mode === 'write' ? 'screen.docs.write' : 'screen.docs.preview')}
          </button>
        ))}
      </div>
      {tab === 'write' ? (
        <textarea
          aria-label={t('screen.docs.body')}
          value={body}
          maxLength={100_000}
          rows={16}
          onChange={(event) => setBody(event.target.value)}
          className={cn(inputClass, 'font-mono text-body-sm')}
        />
      ) : (
        <div className="min-h-40 rounded-md border border-border bg-card p-4">
          {body.trim() === '' ? (
            <p className="text-body-sm text-muted-foreground">{t('screen.docs.emptyBody')}</p>
          ) : (
            <SafeMarkdown>{body}</SafeMarkdown>
          )}
        </div>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-3">
        <Button type="button" variant="secondary" onClick={onDone}>
          {t('screen.docs.cancel')}
        </Button>
        <Button type="button" disabled={save.isPending} onClick={() => submit('CHECK')}>
          {t('screen.docs.save')}
        </Button>
      </div>
      {matches && (
        <Confirm
          title={t('screen.board.item.sensitiveTitle')}
          body={
            <>
              <ul className="flex flex-col gap-1">
                {matches.map((match, index) => (
                  <li key={`${match.masked}-${index}`} className="font-mono break-all">
                    {match.masked}
                  </li>
                ))}
              </ul>
              <p>{t('screen.docs.sensitiveBody')}</p>
            </>
          }
          cancel={t('screen.chat.warning.edit')}
          confirm={t('screen.board.item.mask')}
          onCancel={() => setMatches(undefined)}
          onConfirm={() => submit('MASK')}
        />
      )}
      {stale && (
        <Confirm
          title={t('error.STALE')}
          body={<p>{t('screen.docs.staleBody')}</p>}
          confirm={t('screen.docs.latest')}
          onConfirm={() => {
            setStale(false)
            guard.current = false
            onStale?.()
          }}
        />
      )}
      {blocker.state === 'blocked' && (
        <Confirm
          title={t('screen.docs.leaveTitle')}
          body={<p>{t('screen.docs.leaveBody')}</p>}
          cancel={t('screen.docs.stay')}
          confirm={t('screen.docs.leave')}
          onCancel={() => blocker.reset()}
          onConfirm={() => blocker.proceed()}
        />
      )}
    </section>
  )
}

/** "Lịch sử phiên bản" (FR-29): every version, "Xem" read-only, "Khôi phục" as a new version. */
function VersionHistory({ jobId, page, open, onClose }: { jobId: string; page: PageDoc; open: boolean; onClose: () => void }) {
  const toasts = useToasts()
  const versions = usePageVersions(page.id, open)
  const [viewing, setViewing] = useState<number>()
  const version = usePageVersion(page.id, viewing)
  const restore = useRestorePage(jobId, page.id)
  return (
    <Sheet open={open} onClose={onClose} title={t('screen.docs.history')}>
      <RegionState query={versions} skeleton={<Skeleton className="h-48 w-full" />}>
        <ol className="flex flex-col divide-y divide-border">
          {versions.data?.map((entry) => (
            <li key={entry.versionNo} className="flex flex-col gap-2 py-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-col gap-0.5">
                  <span className="text-label">{t('screen.docs.versionTitle', { n: entry.versionNo, title: entry.title })}</span>
                  <span className="text-caption text-muted-foreground">
                    {entry.author || t('screen.docs.unknownAuthor')} · {formatDayMonthTime(entry.at)}
                    {entry.restoredFrom ? ` · ${t('screen.docs.restoredFrom', { n: entry.restoredFrom })}` : ''}
                  </span>
                </div>
                <div className="flex gap-2">
                  <Button type="button" variant="ghost" size="compact" onClick={() => setViewing(entry.versionNo)}>
                    {t('screen.docs.view')}
                  </Button>
                  {!page.readOnly && entry.versionNo !== page.versionNo && (
                    <Button
                      type="button"
                      variant="secondary"
                      size="compact"
                      disabled={restore.isPending}
                      onClick={() =>
                        restore.mutate(
                          { versionNo: entry.versionNo, version: page.versionNo },
                          {
                            onSuccess: (result) => {
                              toasts.success(t('screen.docs.restored', { n: result.versionNo, from: entry.versionNo }))
                              onClose()
                            },
                            onError: (failure) => toasts.error(problemMessage(failure, { module: 'work' }).text),
                          },
                        )
                      }
                    >
                      {t('screen.docs.restore')}
                    </Button>
                  )}
                </div>
              </div>
              {viewing === entry.versionNo && version.data && (
                <div aria-label={t('screen.docs.versionTitle', { n: entry.versionNo, title: entry.title })} className="rounded-md bg-muted p-3">
                  <SafeMarkdown>{version.data.body}</SafeMarkdown>
                </div>
              )}
            </li>
          ))}
        </ol>
      </RegionState>
    </Sheet>
  )
}

function Confirm({
  title,
  body,
  cancel,
  confirm,
  onCancel,
  onConfirm,
}: {
  title: string
  body: ReactNode
  cancel?: string
  confirm: string
  onCancel?: () => void
  onConfirm: () => void
}) {
  return (
    <Dialog.Root open onOpenChange={(next) => !next && (onCancel ?? onConfirm)()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{title}</Dialog.Title>
          <div className="flex flex-col gap-2 text-body-sm">{body}</div>
          <div className="flex flex-wrap justify-end gap-3">
            {cancel && onCancel && (
              <Button type="button" variant="secondary" onClick={onCancel}>
                {cancel}
              </Button>
            )}
            <Button type="button" onClick={onConfirm}>
              {confirm}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[1280px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {children}
    </div>
  )
}
