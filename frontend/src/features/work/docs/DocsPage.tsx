import { Dialog } from '@base-ui/react/dialog'
import { Menu } from '@base-ui/react/menu'
import { Eye, FileText, History, Plus, ShieldAlert } from 'lucide-react'
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
import { buttonVariants } from '@/components/ui/button-variants'
import { ReportAbuseMenu } from '@/features/abuse/ReportAbuseMenu'
import { ByJobRole, JobRoleLine } from '@/features/job/ByJobRole'
import { useJob } from '@/features/job/jobApi'
import { JobHeader } from '@/features/job/JobHeader'
import { customerMeta, mentorMeta } from '@/features/job/jobMeta'
import { JobTabs } from '@/features/job/JobTabs'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob } from '@/features/matching/mentorJobApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { hasBoard } from '@/features/work/workApi'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { SharedDocsLink } from './SharedDocsPage'
import {
  usePage,
  usePageTree,
  usePageVersion,
  usePageVersions,
  useRestorePage,
  useSavePage,
  useSetVisibility,
  type PageDoc,
  type PageTree,
} from './docsApi'

/**
 * JOB-3 "Tài liệu" (`/cong-viec/{jobId}/tai-lieu/{pageId?}`, FR-29, FR-47, AD-23, UX-DR39): the Page tree
 * (two levels), the Page in the safe Markdown view, the editor with "Soạn" / "Xem trước" and an explicit
 * "Lưu" (each save a version), and "Lịch sử phiên bản" with "Xem" and "Khôi phục". Leaving with unsaved
 * changes asks first; a stale save opens the Stale dialog. The Mentor and the active Crew write; a former
 * member reads; the Job's Customer reads customer-visible Pages only; anyone else sees the not-found
 * screen. "Trang mới" starts blank or from a template (FR-30); only the Mentor switches a Page between
 * "Chỉ nhóm" and "Khách hàng xem được" (FR-31), and the Assessment Report stays customer-visible.
 */
export function DocsPage() {
  const { jobId, pageId } = useParams()
  return (
    <ByJobRole
      jobId={jobId}
      customer={() => <CustomerDocs jobId={jobId ?? ''} pageId={pageId} />}
      mentor={() => <MentorDocs jobId={jobId ?? ''} pageId={pageId} />}
      crew={() => <StudentDocs jobId={jobId ?? ''} pageId={pageId} />}
    />
  )
}

function CustomerDocs({ jobId, pageId }: { jobId: string; pageId: string | undefined }) {
  const job = useJob(jobId)
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
          <JobHeader title={job.data.title} state={job.data.state} meta={customerMeta(job.data)}>
            <JobTabs
              jobId={job.data.id}
              current="docs"
              chat={{ unread: false }}
              agreement={job.data.agreementShared}
              payment={job.data.agreementConfirmed}
              docs
              handover
            />
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

function TemplateBadge({ template }: { template?: string | null }) {
  return template ? (
    <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">
      {t(`screen.docs.template.${template}` as MessageKey)}
    </span>
  ) : null
}

function VisibilityBadge({ visibility }: { visibility: string }) {
  return (
    <span
      className={cn(
        'rounded-full px-2 py-0.5 text-caption font-semibold',
        visibility === 'CUSTOMER' ? 'bg-primary-soft text-primary' : 'bg-muted text-muted-foreground',
      )}
    >
      {t(visibility === 'CUSTOMER' ? 'screen.docs.visibility.CUSTOMER' : 'screen.docs.visibility.TEAM')}
    </span>
  )
}

/** N-12, for the Mentor and the Crew above the Assessment Report (FR-10); never part of the report. */
function SafetyNotice() {
  return (
    <p className="flex gap-2 rounded-md border-l-4 border-warning bg-warning-soft p-3 text-body-sm">
      <ShieldAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      {t('screen.docs.n12')}
    </p>
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
          <JobHeader title={job.data.title} state={job.data.state} meta={mentorMeta(job.data)}>
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
        <JobRoleLine jobId={jobId} />
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
  const navigate = useNavigate()
  const toasts = useToasts()
  const fromTemplate = useSavePage(jobId, undefined)
  const start = (template: PageTree['templates'][number]) =>
    fromTemplate.mutate(
      { title: template.title, body: '', template: template.template, sensitive: 'CHECK' },
      {
        onSuccess: (created) => {
          toasts.success(t('screen.docs.created', { title: created.title }))
          void navigate(`${base}/${created.id}`)
        },
        onError: (failure) => toasts.error(problemMessage(failure, { module: 'work' }).text),
      },
    )
  return (
    <div className="grid gap-5 md:grid-cols-[260px_minmax(0,1fr)]">
      <nav aria-label={t('screen.docs.tree')} className="flex flex-col gap-2">
        {!tree.readOnly && (
          <Menu.Root>
            <Menu.Trigger className={cn(buttonVariants({ size: 'compact' }), 'self-start')}>
              <Plus aria-hidden="true" />
              {t('screen.docs.newPage')}
            </Menu.Trigger>
            <Menu.Portal>
              <Menu.Positioner align="start" sideOffset={4} className="z-[60]">
                <Menu.Popup className="min-w-60 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md">
                  <Menu.Item className={menuItem} onClick={() => setCreating({})}>
                    {t('screen.docs.blank')}
                  </Menu.Item>
                  <Menu.Separator className="my-1 h-px bg-border" />
                  <Menu.Group>
                    <Menu.GroupLabel className="px-3 pt-1 pb-1 text-caption text-muted-foreground">
                      {t('screen.docs.fromTemplate')}
                    </Menu.GroupLabel>
                    {tree.templates.map((template) => (
                      <Menu.Item
                        key={template.template}
                        className={menuItem}
                        disabled={fromTemplate.isPending}
                        onClick={() => start(template)}
                      >
                        {t(`screen.docs.template.${template.template}` as MessageKey)}
                      </Menu.Item>
                    ))}
                  </Menu.Group>
                </Menu.Popup>
              </Menu.Positioner>
            </Menu.Portal>
          </Menu.Root>
        )}
        {tree.pages.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">
            {t(tree.customer ? 'screen.docs.emptyCustomer' : 'screen.docs.empty')}
          </p>
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
                  {!tree.customer && page.visibility === 'CUSTOMER' && (
                    <>
                      <Eye aria-hidden="true" className="size-3.5 shrink-0 text-primary" />
                      <span className="sr-only">{t('screen.docs.visibility.CUSTOMER')}</span>
                    </>
                  )}
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
        {!tree.customer && <SharedDocsLink jobId={jobId} />}
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
  const visibility = useSetVisibility(jobId, pageId)
  const report = page.data?.template === 'ASSESSMENT_REPORT'
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
            {report && !tree.customer && <SafetyNotice />}
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="flex min-w-0 flex-col gap-1">
                <div className="flex flex-wrap items-center gap-2">
                  <TemplateBadge template={page.data.template} />
                  {!tree.customer && <VisibilityBadge visibility={page.data.visibility} />}
                </div>
                <h2 className="text-h2 break-words">{page.data.title}</h2>
                <p className="text-caption text-muted-foreground">
                  {t('screen.docs.versionLine', {
                    n: page.data.versionNo,
                    author: page.data.author || t('screen.docs.unknownAuthor'),
                    time: formatDayMonthTime(page.data.at),
                  })}
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <ReportAbuseMenu
                  targets={[{ kind: 'PAGE', targetId: page.data.id }]}
                  label={t('screen.report.pageMore', { title: page.data.title })}
                />
                {page.data.mentor && !page.data.readOnly && (
                  <label className="flex min-h-tap-min items-center gap-2 text-body-sm">
                    <input
                      type="checkbox"
                      role="switch"
                      checked={page.data.visibility === 'CUSTOMER'}
                      disabled={report || visibility.isPending}
                      aria-describedby={report ? `${pageId}-report` : undefined}
                      onChange={(event) => visibility.mutate(event.target.checked ? 'CUSTOMER' : 'TEAM')}
                      className="size-4"
                    />
                    {t('screen.docs.visibility.CUSTOMER')}
                  </label>
                )}
                {report && page.data.mentor && (
                  <span id={`${pageId}-report`} className="text-caption text-muted-foreground">
                    {t('screen.docs.reportVisible')}
                  </span>
                )}
                {!tree.customer && (
                  <Button type="button" variant="secondary" size="compact" onClick={() => setHistory(true)}>
                    <History aria-hidden="true" />
                    {t('screen.docs.history')}
                  </Button>
                )}
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
            {!tree.customer && (
              <VersionHistory jobId={jobId} page={page.data} open={history} onClose={() => setHistory(false)} />
            )}
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
      {page?.template === 'ASSESSMENT_REPORT' && <SafetyNotice />}
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

const menuItem =
  'flex min-h-tap-min cursor-pointer items-center rounded-md px-3 text-body-sm data-disabled:cursor-not-allowed data-disabled:opacity-50 data-highlighted:bg-muted'

function Frame({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[1280px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {children}
    </div>
  )
}
