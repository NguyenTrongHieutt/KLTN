import { Dialog } from '@base-ui/react/dialog'
import { CircleCheck, Info, Users, X } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { Navigate } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { hasChat, useJobChat } from '@/features/chat/chatApi'
import { useJob, type Job } from '@/features/job/jobApi'
import { JobHeader } from '@/features/job/JobHeader'
import { customerMeta } from '@/features/job/jobMeta'
import { JobTabs } from '@/features/job/JobTabs'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { AgreementContentView, TestTag } from './AgreementContent'
import {
  useApproveAgreement,
  useCustomerAgreement,
  useRequestAgreementChanges,
  type AgreementVersion,
  type ConfirmedAgreement,
} from './agreementApi'
import { draftDays, inOrder, moved } from './reviewTargets'

const TOOLS = ['LOVABLE', 'BOLT', 'REPLIT', 'V0', 'BASE44', 'CURSOR', 'CLAUDE_CODE', 'CODEX', 'OTHER'] as const

/**
 * The Customer's JOB-6 (FR-16, FR-17, UX-DR34): the shared versions with "Góp ý", "Lên"/"Xuống", the
 * live-system choice (N-7), N-6, "Yêu cầu sửa" and "Duyệt thỏa thuận", each at the `Job.version` the
 * screen loaded; once confirmed, the Agreement version with its confirmers and the Crew's usernames.
 */
export function CustomerAgreement({ jobId }: { jobId: string }) {
  const job = useJob(jobId)
  const [version, setVersion] = useState<number>()
  const agreement = useCustomerAgreement(jobId, version)
  usePageTitle(job.data ? t('screen.agreement.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && !job.data.agreementShared) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
        {job.data && <CustomerHeader job={job.data} />}
      </RegionState>
      <RegionState query={agreement} skeleton={<Skeleton className="h-96 w-full" />}>
        {agreement.data && job.data && (
          <div className="flex w-full max-w-[820px] flex-col gap-5 self-center">
            <div className="flex flex-wrap items-end justify-between gap-3">
              {agreement.data.versions.length > 1 && (
                <VersionPicker
                  versions={agreement.data.versions}
                  value={agreement.data.versionNo}
                  confirmed={agreement.data.confirmed}
                  onChange={setVersion}
                />
              )}
              <p className="text-body-sm text-muted-foreground">
                {t('screen.agreement.sentBy', {
                  n: agreement.data.versionNo,
                  time: formatDayMonthTime(agreement.data.sharedAt),
                })}
              </p>
            </div>
            <Review key={`${agreement.data.versionNo}-${job.data.version}`} job={job.data} agreement={agreement.data} />
          </div>
        )}
      </RegionState>
    </div>
  )
}

function Review({ job, agreement }: { job: Job; agreement: AgreementVersion }) {
  const latest = agreement.versionNo === agreement.latestVersionNo
  const confirmed = agreement.confirmed?.draftVersionNo === agreement.versionNo ? agreement.confirmed : undefined
  const reviewable = latest && !agreement.confirmed && job.state === 'AGREEMENT'
  const decidable = reviewable && !agreement.approvedAt && !agreement.changeRequested
  const original = agreement.content.milestones.map((milestone) => milestone.id)
  const [order, setOrder] = useState(original)
  const [live, setLive] = useState<Set<string>>(() => new Set(confirmed?.liveAccessIds ?? []))
  const [asking, setAsking] = useState<string>()
  const [freeze, setFreeze] = useState(false)
  const [dialog, setDialog] = useState<'changes' | 'approve'>()
  const reordered = order.join() !== original.join()
  const codeJob = agreement.content.codeJob
  const blocked = reordered ? 'order' : codeJob && !freeze ? 'freeze' : undefined

  return (
    <>
      <Status agreement={agreement} confirmed={confirmed} latest={latest} />
      {agreement.quote && <Compare agreement={agreement} />}
      <CrewLine crew={confirmed?.crew ?? agreement.crew} confirmed={agreement.crewConfirmed || !!confirmed} />
      <AgreementContentView
        content={agreement.content}
        quotePrice={agreement.quote?.priceVnd}
        milestones={inOrder(agreement.content.milestones, order)}
        review={{
          jobId: job.id,
          versionNo: agreement.versionNo,
          comments: agreement.comments,
          canComment: reviewable,
          onMove: decidable ? (index, delta) => setOrder(moved(order, index, delta)) : undefined,
          live,
          onLive: decidable
            ? (id, next) => {
                if (next) {
                  setAsking(id)
                } else {
                  setLive(new Set([...live].filter((entry) => entry !== id)))
                }
              }
            : undefined,
        }}
      />
      {decidable && (
        <>
          {reordered && (
            <div role="status" className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted p-3 text-body-sm">
              <span>{t('screen.agreement.review.reordered')}</span>
              <Button type="button" variant="ghost" size="compact" onClick={() => setOrder(original)}>
                {t('screen.agreement.review.resetOrder')}
              </Button>
            </div>
          )}
          {codeJob && <FreezeCommitment checked={freeze} onChange={setFreeze} />}
          <p className="text-body-sm text-muted-foreground">{t('screen.agreement.review.afterApprove')}</p>
          <div className="flex flex-wrap justify-between gap-3">
            <Button type="button" variant="secondary" onClick={() => setDialog('changes')}>
              {t('screen.agreement.review.requestChanges')}
            </Button>
            <Button type="button" disabled={blocked !== undefined} aria-describedby="approve-why" onClick={() => setDialog('approve')}>
              {t('screen.agreement.review.approve')}
            </Button>
          </div>
          {blocked && (
            <p id="approve-why" className="text-caption text-muted-foreground">
              {t(blocked === 'order' ? 'screen.agreement.review.approveBlockedOrder' : 'screen.agreement.review.approveBlockedFreeze')}
            </p>
          )}
        </>
      )}
      {!agreement.confirmed && !decidable && <p className="text-body-sm text-muted-foreground">{t('screen.agreement.next')}</p>}
      {dialog === 'changes' && (
        <ChangesDialog job={job} agreement={agreement} order={reordered ? order : undefined} onClose={() => setDialog(undefined)} />
      )}
      {dialog === 'approve' && (
        <ApproveDialog job={job} agreement={agreement} freeze={freeze} live={[...live]} onClose={() => setDialog(undefined)} />
      )}
      <ChoiceDialog
        open={asking !== undefined}
        title={t('screen.agreement.review.n7Title')}
        primary={{ label: t('screen.agreement.review.n7Test'), onClick: () => setAsking(undefined) }}
        secondary={{
          label: t('screen.agreement.review.n7Live'),
          onClick: () => {
            if (asking) {
              setLive(new Set([...live, asking]))
            }
            setAsking(undefined)
          },
        }}
        onClose={() => setAsking(undefined)}
      >
        <p className="text-body-sm">{t('screen.agreement.review.n7Body')}</p>
      </ChoiceDialog>
    </>
  )
}

function Status({ agreement, confirmed, latest }: { agreement: AgreementVersion; confirmed?: ConfirmedAgreement; latest: boolean }) {
  if (confirmed) {
    return (
      <section role="status" className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4 text-body-sm">
        <p className="flex items-center gap-2 font-semibold">
          <CircleCheck aria-hidden="true" className="size-5 text-success" />
          {t('screen.agreement.review.confirmedLine')}
        </p>
        <p className="text-muted-foreground">{t('screen.agreement.review.confirmedAt', { time: formatDayMonthTime(confirmed.confirmedAt) })}</p>
      </section>
    )
  }
  const text = !latest
    ? t('screen.agreement.review.statusOld', { n: agreement.latestVersionNo })
    : agreement.approvedAt
      ? t('screen.agreement.review.statusApproved', { n: agreement.versionNo, time: formatDayMonthTime(agreement.approvedAt) })
      : agreement.changeRequested
        ? t('screen.agreement.review.statusChanges', { n: agreement.versionNo })
        : undefined
  return text ? (
    <p role="status" className="flex gap-2 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
      <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
      {text}
    </p>
  ) : null
}

/** The draft's price and timeline beside the Application's quote (FR-16), differing rows highlighted. */
function Compare({ agreement }: { agreement: AgreementVersion }) {
  const quote = agreement.quote
  if (!quote) {
    return null
  }
  const days = draftDays(
    agreement.agreementStartedAt,
    agreement.content.milestones.map((milestone) => milestone.dueOn),
  )
  const rows: { label: MessageKey; value: ReactNode; quoted: string; same: boolean }[] = [
    {
      label: 'screen.agreement.review.comparePrice',
      value: agreement.content.priceVnd == null ? '—' : (
        <>
          {formatVnd(agreement.content.priceVnd)} <TestTag />
        </>
      ),
      quoted: formatVnd(quote.priceVnd),
      same: agreement.content.priceVnd === quote.priceVnd,
    },
    {
      label: 'screen.agreement.review.compareTimeline',
      value: days === undefined ? '—' : t('screen.agreement.review.days', { n: days }),
      quoted: t('screen.agreement.review.days', { n: quote.timelineDays }),
      same: days === quote.timelineDays,
    },
  ]
  return (
    <section className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
      <h2 className="text-h2">{t('screen.agreement.review.compareTitle')}</h2>
      <ul className="flex flex-col gap-1.5 text-body-sm">
        {rows.map((row) => (
          <li
            key={row.label}
            className={cn('flex flex-wrap items-center justify-between gap-2 rounded-md px-3 py-2', !row.same && 'bg-warning-soft')}
          >
            <span>
              <span className="font-semibold">{t(row.label)}:</span> {row.value}
            </span>
            <span className="text-caption">
              {t('screen.agreement.review.quoteSide', { value: row.quoted })} ·{' '}
              <span className={cn('font-semibold', !row.same && 'text-warning')}>
                {t(row.same ? 'screen.agreement.review.same' : 'screen.agreement.review.differs')}
              </span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * "Nhóm thực hiện" for the Customer (FR-75, R-1 L4): the GitHub usernames of the Crew only, never a
 * share, with "Chờ nhóm thực hiện xác nhận" until FR-17's Crew condition holds.
 */
function CrewLine({ crew, confirmed }: { crew: string[]; confirmed: boolean }) {
  return (
    <section className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4 text-body-sm">
      <p className="flex flex-wrap items-center gap-2">
        <Users aria-hidden="true" className="size-4 text-primary" />
        <span className="font-semibold">{t('screen.crew.customer.title')}:</span>
        {crew.length > 0 ? (
          <span className="font-mono break-words">{crew.join(' · ')}</span>
        ) : (
          <span className="text-muted-foreground">{t('screen.crew.customer.none')}</span>
        )}
        {!confirmed && (
          <span className="rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold text-warning">
            {t('screen.crew.customer.waiting')}
          </span>
        )}
      </p>
      <p className="text-caption text-muted-foreground">{t('screen.crew.customer.note')}</p>
    </section>
  )
}

/** N-6, bordered, with "Cách tạm dừng đồng bộ" (draft per-builder text until OQ7). */
function FreezeCommitment({ checked, onChange }: { checked: boolean; onChange: (checked: boolean) => void }) {
  const ids = useId()
  const [help, setHelp] = useState(false)
  return (
    <div className="flex flex-col gap-2 rounded-lg border-2 border-border bg-card p-4">
      <label className="flex items-start gap-3 text-body-sm">
        <input type="checkbox" className="mt-1" checked={checked} onChange={(event) => onChange(event.target.checked)} />
        <span>{t('screen.agreement.review.n6')}</span>
      </label>
      <Button
        type="button"
        variant="ghost"
        size="compact"
        className="self-start"
        aria-expanded={help}
        aria-controls={`${ids}-pause`}
        onClick={() => setHelp(!help)}
      >
        {t('screen.agreement.review.pauseLink')}
      </Button>
      {help && (
        <div id={`${ids}-pause`} className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
          <p>{t('screen.agreement.review.pauseLead')}</p>
          <ul className="flex list-disc flex-col gap-1 pl-5">
            {TOOLS.map((tool) => (
              <li key={tool}>{t(`screen.agreement.review.pauseHelp.${tool}`)}</li>
            ))}
          </ul>
          <p className="text-caption text-muted-foreground">{t('screen.agreement.review.pauseDraft')}</p>
        </div>
      )}
    </div>
  )
}

function decisionFailed(error: unknown): string {
  return error instanceof ApiError && error.problem.code === 'STALE'
    ? t('error.STALE')
    : problemMessage(error, { module: 'job', action: t('screen.agreement.review.decisionAction') }).text
}

function ChangesDialog({
  job,
  agreement,
  order,
  onClose,
}: {
  job: Job
  agreement: AgreementVersion
  order?: string[]
  onClose: () => void
}) {
  const ids = useId()
  const [note, setNote] = useState('')
  const send = useRequestAgreementChanges(job.id)
  const toasts = useToasts()
  const submit = () =>
    send.mutate(
      { version: job.version, versionNo: agreement.versionNo, note: note.trim(), milestoneOrder: order },
      {
        onSuccess: () => {
          toasts.success(t('screen.agreement.review.changesSent'))
          onClose()
        },
        onError: (error) => {
          onClose()
          toasts.error(decisionFailed(error))
        },
      },
    )
  return (
    <ChoiceDialog
      open
      title={t('screen.agreement.review.changesTitle', { n: agreement.versionNo })}
      primary={{ label: t('screen.agreement.review.changesSend'), onClick: submit, disabled: note.trim() === '' || send.isPending }}
      secondary={{ label: t('screen.agreement.review.back'), onClick: onClose }}
      onClose={onClose}
    >
      <p className="text-body-sm">{t('screen.agreement.review.changesLead')}</p>
      <label htmlFor={`${ids}-note`} className="text-label">
        {t('screen.agreement.review.changesNote')}
      </label>
      <textarea
        id={`${ids}-note`}
        value={note}
        maxLength={2000}
        onChange={(event) => setNote(event.target.value)}
        className={cn(inputClass, 'min-h-24')}
      />
      {order && <p className="text-caption text-muted-foreground">{t('screen.agreement.review.changesOrder')}</p>}
    </ChoiceDialog>
  )
}

function ApproveDialog({
  job,
  agreement,
  freeze,
  live,
  onClose,
}: {
  job: Job
  agreement: AgreementVersion
  freeze: boolean
  live: string[]
  onClose: () => void
}) {
  const approve = useApproveAgreement(job.id)
  const toasts = useToasts()
  const submit = () =>
    approve.mutate(
      { version: job.version, versionNo: agreement.versionNo, freezeCommitment: freeze, liveAccessIds: live },
      {
        onSuccess: () => {
          toasts.success(t('screen.agreement.review.approved'))
          onClose()
        },
        onError: (error) => {
          onClose()
          toasts.error(decisionFailed(error))
        },
      },
    )
  return (
    <ChoiceDialog
      open
      title={t('screen.agreement.review.approveTitle', { n: agreement.versionNo })}
      primary={{ label: t('screen.agreement.review.approveConfirm'), onClick: submit, disabled: approve.isPending }}
      secondary={{ label: t('screen.agreement.review.back'), onClick: onClose }}
      onClose={onClose}
    >
      <p className="text-body-sm">{t('screen.agreement.review.approveWhat')}</p>
      <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
        <li>{t('screen.agreement.review.approveWhatMentor')}</li>
        <li>{t('screen.agreement.review.approveWhatEffect')}</li>
        <li>{t('screen.agreement.review.approveWhatPay')}</li>
        {agreement.content.codeJob && <li>{t('screen.agreement.review.approveWhatFreeze')}</li>}
        {live.length > 0 && <li>{t('screen.agreement.review.approveWhatLive', { n: live.length })}</li>}
      </ul>
    </ChoiceDialog>
  )
}

/** A dialog with one primary action and one other choice (DESIGN.md Confirmation dialog). */
function ChoiceDialog({
  open,
  title,
  primary,
  secondary,
  onClose,
  children,
}: {
  open: boolean
  title: string
  primary: { label: string; onClick: () => void; disabled?: boolean }
  secondary: { label: string; onClick: () => void }
  onClose: () => void
  children: ReactNode
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-3 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2 break-words">{title}</Dialog.Title>
            <Dialog.Close aria-label={t('screen.product.finding.close')} className="rounded-md p-1 text-muted-foreground hover:bg-muted">
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          {children}
          <div className="mt-1 flex flex-wrap justify-between gap-3">
            <Button type="button" variant="secondary" onClick={secondary.onClick}>
              {secondary.label}
            </Button>
            <Button type="button" disabled={primary.disabled} onClick={primary.onClick}>
              {primary.label}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function CustomerHeader({ job }: { job: Job }) {
  const chat = useJobChat(hasChat(job.state) ? job.id : undefined)
  return (
    <JobHeader title={job.title} state={job.state} meta={customerMeta(job)}>
      <JobTabs
        jobId={job.id}
        current="agreement"
        chat={hasChat(job.state) ? { unread: chat.data?.unread ?? false } : undefined}
        agreement
        payment={job.agreementConfirmed}
      />
    </JobHeader>
  )
}

function VersionPicker({
  versions,
  value,
  confirmed,
  onChange,
}: {
  versions: { versionNo: number; sharedAt: string }[]
  value: number
  confirmed?: ConfirmedAgreement
  onChange: (version: number) => void
}) {
  const ids = useId()
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={`${ids}-version`} className="text-label">
        {t('screen.agreement.version')}
      </label>
      <select id={`${ids}-version`} value={value} onChange={(event) => onChange(Number(event.target.value))} className={inputClass}>
        {[...versions].reverse().map((version) => (
          <option key={version.versionNo} value={version.versionNo}>
            {confirmed?.draftVersionNo === version.versionNo
              ? t('screen.agreement.review.confirmedOption', { n: confirmed.versionNo, date: formatDayMonth(confirmed.confirmedAt) })
              : t('screen.agreement.versionOption', { n: version.versionNo, date: formatDayMonth(version.sharedAt) })}
          </option>
        ))}
      </select>
    </div>
  )
}
