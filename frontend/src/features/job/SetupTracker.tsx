import { Dialog } from '@base-ui/react/dialog'
import { useQueryClient } from '@tanstack/react-query'
import { Circle, CircleAlert, CircleCheck, CircleDot, CircleX, Copy, ExternalLink, TriangleAlert } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { Sheet } from '@/components/Sheet'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { useWorkflowFile } from '@/features/product/productApi'
import { workflowActionsUrl } from '@/features/product/productText'
import { OutLink, SecretGuidance, WorkflowFileActions } from '@/features/product/WorkflowSetup'
import { rich } from '@/i18n/rich'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { ActionsRunRow, type RunState } from './ActionsRunRow'
import {
  SELF_CONFIRMATION_NOTICE_VERSION,
  setupKey,
  useConfirmSourceClean,
  useRetrySetup,
  useSetup,
  type Setup,
} from './setupApi'

type Person = Setup['people'][number]
type Head = NonNullable<Setup['head']>

/** GitHub's own page for turning on two-factor authentication. */
const TWO_FACTOR_SETTINGS = 'https://github.com/settings/security'

/** The Source checks' blocks (Story 23.5), which the Customer fixes on GitHub and the confirmation may answer with. */
const SOURCE_BLOCKS = [
  'SOURCE_APP_MISSING',
  'SOURCE_APP_WRONG_REPOSITORY',
  'WORKFLOW_MISSING',
  'WORKFLOW_MISMATCH',
  'NO_ADMIN_PERMISSION',
]

/** The Source App's blocks, which the Product's page (CUS-4, Story 23.5c) fixes. */
const SOURCE_APP_BLOCKS = ['SOURCE_APP_MISSING', 'SOURCE_APP_WRONG_REPOSITORY']

/** The copy run's own blocks (Story 23.6b), which its Actions run status row names. */
const RUN_BLOCKS = ['EXCHANGE_REFUSED', 'RUN_FAILED', 'ACTIONS_QUOTA']

/** The exchange refused a `vibefix.yml` that is no published one (AD-44): the Customer adds the file again. */
const CHANGED_WORKFLOW = 'blob'

/** The exchange refused a head that moved after the confirmation: the Customer's step opens again at the new head. */
const HEAD_MOVED = 'sha'

/** The tracker's hints for putting `vibefix.yml` back on GitHub, whose way back is "Thử lại". */
const WORKFLOW_HINTS = { add: 'screen.job.setup.addWorkflowHint', edit: 'screen.job.setup.editWorkflowHint' } as const

/**
 * The viewer's own pending invitation: only their own sub-row carries a GitHub link. GitHub greys out
 * "Accept invitation" until two-factor authentication is on, and no API reports it before acceptance
 * (Spike S4, 2026-10-22), so that row always says 2FA comes first.
 */
function ownInvited(person: Person): boolean {
  return person.state === 'INVITED' && !!person.githubUrl
}

const ICONS = {
  DONE: CircleCheck,
  CURRENT: CircleDot,
  PENDING: Circle,
  BLOCKED: CircleAlert,
  FAILED: CircleX,
} as const

const TONES: Record<string, string> = {
  DONE: 'text-success',
  CURRENT: 'text-primary',
  PENDING: 'text-muted-foreground',
  BLOCKED: 'text-warning',
  FAILED: 'text-destructive',
}

/**
 * JOB-H's step tracker while a Code Job is Starting (UX-DR114, UX-DR117, UX-DR118, AD-11, Story 23.6c), in
 * EXPERIENCE.md's order: "Kiểm tra ứng dụng vibefix và file workflow", the self-confirmation (the Customer's SHA
 * confirmation card with N-32; everyone else waits for it, then reads the confirmed head), "Tạo kho trung gian và kho
 * làm việc (riêng tư)", the copy on the Customer's GitHub Actions with its Actions run status row, "Mời trên GitHub"
 * with a sub-row per person (the Customer's read of the Intermediary Repository last, never holding the step, Story
 * 23.7), and "Cấp quyền ghi và kiểm tra", each done, current, pending, blocked, or failed, read
 * every 3 s. The viewer's role in the Job comes with the Setup, so the words never wait on another read. The current
 * step is announced politely. A block reads its reason in the words of the viewer's role: the Customer reads what to do
 * with the means to do it and, where it helps, "Thử lại"; the Mentor and the Crew read the same reason without the
 * Customer's actions. The Customer reads "Bạn không cần làm gì" only while the current step is not theirs.
 */
export function SetupTracker({ jobId }: { jobId: string }) {
  const setup = useSetup(jobId)
  if (!setup.data) {
    return null
  }
  return <Steps setup={setup.data} />
}

/**
 * A step's label: the Customer's own confirmation is "Bạn xác nhận…", and the copy is "của bạn" for the Customer, whose
 * Actions run it, and "của khách hàng" for the team.
 */
function stepLabel(key: string, customer: boolean): string {
  const variant = key === 'CONFIRM' && customer ? 'CONFIRMCustomer' : key === 'COPY' && !customer ? 'COPYTeam' : key
  return t(`screen.job.setup.step${variant}` as MessageKey)
}

/**
 * A block's plain text, by its reason and the viewer's role (EXPERIENCE.md's reason inventory), and whether the
 * Customer's way forward is "Thử lại". An exchange refusal other than a changed `vibefix.yml` is vibefix's to look
 * into, so nobody is asked to act on it. A reason the tracker does not know reads neutrally to the team.
 */
function blockText(setup: Setup, customer: boolean): { text: string; retry: boolean } {
  const reason = setup.blockedReason
  const changedWorkflow = reason === 'EXCHANGE_REFUSED' && setup.exchangeReason === CHANGED_WORKFLOW
  const key = changedWorkflow ? 'ExchangeBlob' : reason
  const known = reason !== undefined && (SOURCE_BLOCKS.includes(reason) || RUN_BLOCKS.includes(reason))
  if (!customer) {
    const team: MessageKey = known ? (`screen.job.setup.team${key}` as MessageKey) : 'screen.job.setup.teamUnknown'
    return { text: t(team), retry: false }
  }
  if (!known) {
    return { text: t('screen.job.setup.blockedUnknown'), retry: true }
  }
  const own = `screen.job.setup.blocked${key}` as MessageKey
  return { text: t(own), retry: reason !== 'EXCHANGE_REFUSED' || changedWorkflow }
}

/** What a failed command reads, a busy Job named by what it is doing. */
function commandProblem(error: unknown): string {
  return problemMessage(error, { module: 'repo', operation: t('screen.job.setup.busyOperation') }).text
}

function Steps({ setup }: { setup: Setup }) {
  const customer = setup.viewer === 'CUSTOMER'
  const retry = useRetrySetup(setup.jobId)
  const current = setup.steps.find((step) => step.state !== 'DONE')
  const announcement = current
    ? t('screen.job.setup.announce', {
        step: stepLabel(current.key, customer),
        state: t(`screen.job.setup.state${current.state}` as MessageKey),
      })
    : t('screen.job.setup.allDone')
  const block = setup.status === 'BLOCKED' ? blockText(setup, customer) : undefined
  // A block of the copy run is named in its Actions run status row; any other below the steps.
  const runBlock =
    block !== undefined &&
    RUN_BLOCKS.includes(setup.blockedReason ?? '') &&
    setup.steps.some((step) => step.key === 'COPY' && step.state === 'BLOCKED')
  const retryButton =
    block?.retry && setup.canRetry ? (
      <Button className="w-fit" disabled={retry.isPending} onClick={() => retry.mutate()}>
        {t('screen.job.setup.retry')}
      </Button>
    ) : undefined
  const fix = customer && block ? <BlockFix setup={setup} /> : undefined
  // The Customer's own step: the SHA confirmation card is theirs to act on.
  const confirming =
    customer &&
    setup.status === 'CONFIRMING' &&
    setup.steps.some((step) => step.key === 'CONFIRM' && step.state === 'CURRENT')
  const nothingToDo =
    ['NONE', 'CONFIRMING', 'RUNNING', 'DONE'].includes(setup.status) && !confirming && !setup.people.some(ownInvited)
  return (
    <section aria-labelledby="job-setup" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <h2 id="job-setup" className="text-h3">
        {t('screen.job.setup.title')}
      </h2>
      <ol className="flex flex-col gap-2">
        {setup.steps.map((step) => {
          const Icon = ICONS[step.state as keyof typeof ICONS] ?? Circle
          return (
            <li key={step.key} className="flex flex-wrap items-center gap-2 text-body-sm">
              <Icon aria-hidden="true" className={`size-5 shrink-0 ${TONES[step.state] ?? ''}`} />
              <span className={step.state === 'PENDING' ? 'text-muted-foreground' : 'font-semibold'}>
                {stepLabel(step.key, customer)}
              </span>
              <span className="text-caption text-muted-foreground">· {t(`screen.job.setup.state${step.state}` as MessageKey)}</span>
              {step.key === 'CONFIRM' && <Confirmation setup={setup} state={step.state} customer={customer} />}
              {step.key === 'COPY' && (
                <CopyRun
                  setup={setup}
                  state={step.state}
                  customer={customer}
                  reason={runBlock ? block?.text : undefined}
                  action={
                    runBlock && (fix || retryButton) ? (
                      <>
                        {fix}
                        {retryButton}
                      </>
                    ) : undefined
                  }
                />
              )}
              {step.key === 'INVITE' && setup.people.length > 0 && <People people={setup.people} customer={customer} />}
            </li>
          )
        })}
      </ol>
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>
      {block && !runBlock && (
        <div className="flex flex-col gap-2">
          <p className="text-body-sm">{block.text}</p>
          {fix}
          {retryButton}
        </div>
      )}
      {retry.isError && (
        <p role="alert" className="text-body-sm text-destructive">
          {commandProblem(retry.error)}
        </p>
      )}
      {setup.status === 'FAILED' && <p className="text-body-sm">{t('screen.job.setup.failed')}</p>}
      {setup.status === 'ENDED' && <p className="text-body-sm">{t('screen.job.setup.ended')}</p>}
      {nothingToDo && <p className="text-body-sm text-muted-foreground">{t('screen.job.setup.nothingToDo')}</p>}
    </section>
  )
}

/**
 * The Customer's means to fix a block they are told to fix: the published `vibefix.yml` to put back on GitHub (the
 * Workflow setup card's actions) for a missing or different file, and the Product's page, where Story 23.5c's card
 * fixes the Source App, for the App's blocks. Nothing for the other reasons.
 */
function BlockFix({ setup }: { setup: Setup }) {
  const reason = setup.blockedReason ?? ''
  const changedWorkflow = reason === 'EXCHANGE_REFUSED' && setup.exchangeReason === CHANGED_WORKFLOW
  if (reason === 'WORKFLOW_MISSING' || reason === 'WORKFLOW_MISMATCH' || changedWorkflow) {
    return <WorkflowFix setup={setup} existing={reason !== 'WORKFLOW_MISSING'} />
  }
  if (SOURCE_APP_BLOCKS.includes(reason)) {
    return (
      <Link
        to={`/san-pham/${setup.productId}`}
        className="w-fit text-body-sm text-primary underline-offset-4 hover:underline"
      >
        {t('screen.job.setup.openProduct')}
      </Link>
    )
  }
  return null
}

/** The Workflow setup card's actions for the Source Repository the Setup names, once the published file is read. */
function WorkflowFix({ setup, existing }: { setup: Setup; existing: boolean }) {
  const file = useWorkflowFile()
  if (!file.data || !setup.sourceUrl || !setup.defaultBranch || !setup.sourceFullName) {
    return null
  }
  return (
    <WorkflowFileActions
      file={file.data}
      htmlUrl={setup.sourceUrl}
      branch={setup.defaultBranch}
      repo={setup.sourceFullName}
      existing={existing}
      recheck={false}
      hints={WORKFLOW_HINTS}
    />
  )
}

/** A commit's short SHA, in monospace. */
function Sha({ sha }: { sha: string }) {
  return <code className="font-mono text-mono">{sha.slice(0, 7)}</code>
}

/**
 * Under the self-confirmation step: the Customer's SHA confirmation card while it waits for them, and once confirmed
 * "Đã xác nhận lần lưu {shortSha} lúc {time}", read-only. The Mentor and the Crew read "Chờ khách hàng xác nhận", or
 * that the head moved after the confirmation, then "Đã xác nhận lần lưu {shortSha}".
 */
function Confirmation({ setup, state, customer }: { setup: Setup; state: string; customer: boolean }) {
  const confirmation = setup.confirmation
  if (state === 'DONE' && confirmation) {
    return (
      <p className="w-full pl-7 text-caption text-muted-foreground">
        {customer
          ? rich('screen.job.setup.confirmedCustomer', {
              sha: <Sha sha={confirmation.sha} />,
              time: formatDayMonthTime(confirmation.confirmedAt),
            })
          : rich('screen.job.setup.confirmedTeam', { sha: <Sha sha={confirmation.sha} /> })}
      </p>
    )
  }
  if (state !== 'CURRENT' || setup.status !== 'CONFIRMING') {
    return null
  }
  const moved = setup.exchangeReason === HEAD_MOVED
  if (customer && setup.head) {
    return (
      <div className="w-full pl-7">
        <ShaConfirmationCard setup={setup} head={setup.head} moved={moved} />
      </div>
    )
  }
  return (
    <p className="w-full pl-7 text-caption text-muted-foreground">
      {moved && setup.head
        ? rich('screen.job.setup.headMovedTeam', { sha: <Sha sha={setup.head.sha} /> })
        : t('screen.job.setup.waitingConfirmation')}
    </p>
  )
}

/**
 * The SHA confirmation card (EXPERIENCE.md, N-32, R4, FR-8): the Source Repository the Setup read and its head as "Lần
 * lưu mới nhất: {shortSha} · {date}" with a copy button, N-32's title and checkbox for that head, N-2's guidance in a
 * Sheet and the optional gitleaks run on GitHub, and "Xác nhận và bắt đầu sao chép", disabled until ticked and the
 * repository is named. The confirmation names the head shown and N-32's version.
 * - `STALE` with the head read now: the Stale dialog explains, and the card shows the new head, unticked; nothing is
 *   sent again by itself. Any other `STALE`, such as a Job that left Starting, reads its text after a reload.
 * - `NOTICE_REQUIRED`: N-32 changed on the server, so the page reloads for the new text.
 * - After the copy run was refused because the head moved after the confirmation, the card comes back saying so.
 */
function ShaConfirmationCard({ setup, head, moved }: { setup: Setup; head: Head; moved: boolean }) {
  const jobId = setup.jobId
  const client = useQueryClient()
  const toasts = useToasts()
  const confirm = useConfirmSourceClean(jobId)
  // The head the box was ticked for: a new head is a new confirmation, so it starts unticked.
  const [tickedSha, setTickedSha] = useState<string>()
  const [guidance, setGuidance] = useState(false)
  const [stale, setStale] = useState(false)
  const [noticeChanged, setNoticeChanged] = useState(false)
  const [problem, setProblem] = useState<string>()
  const ticked = tickedSha === head.sha
  const repo = setup.sourceFullName

  function copySha() {
    void Promise.resolve()
      .then(() => navigator.clipboard.writeText(head.sha))
      .then(() => toasts.success(t('screen.job.setup.card.shaCopied')))
      .catch(() => toasts.error(t('screen.job.setup.card.copyFailed')))
  }

  function submit() {
    setProblem(undefined)
    confirm.mutate(head.sha, {
      onError: (error) => {
        const answer = error instanceof ApiError ? error.problem : undefined
        if (answer?.code === 'STALE' && answer.head) {
          setStale(true)
          return
        }
        if (answer?.code === 'VALIDATION' && answer.reason === 'NOTICE_REQUIRED') {
          setNoticeChanged(true)
          return
        }
        // The Source App or the workflow failed its check: the Setup is blocked now, and the tracker says why.
        const blocked = answer?.code === 'GUARD_FAILED' && SOURCE_BLOCKS.includes(answer.reason ?? '')
        if (blocked || answer?.code === 'STALE' || answer?.code === 'CONFLICT') {
          void client.invalidateQueries({ queryKey: setupKey(jobId) })
        }
        if (!blocked) {
          setProblem(staleText(error) ?? commandProblem(error))
        }
      },
    })
  }

  return (
    <div className="flex flex-col gap-3 rounded-md border border-primary-border bg-background p-4">
      {moved && (
        <p role="alert" className="flex gap-2 rounded-md bg-warning-soft p-3 text-body-sm">
          <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warning" />
          <span>{rich('screen.job.setup.card.headMoved', { sha: <Sha sha={head.sha} /> })}</span>
        </p>
      )}
      <div className="flex flex-col gap-1">
        {repo && <p className="text-body-sm break-words">{t('screen.job.setup.card.source', { repo })}</p>}
        <p className="flex flex-wrap items-center gap-1 text-body-sm">
          <span>
            {rich('screen.job.setup.card.head', { sha: <Sha sha={head.sha} />, date: formatDayMonthTime(head.readAt) })}
          </span>
          <Button
            type="button"
            variant="ghost"
            size="compact"
            className="px-2"
            aria-label={t('screen.job.setup.card.copySha')}
            onClick={copySha}
          >
            <Copy aria-hidden="true" className="size-4" />
          </Button>
        </p>
      </div>
      <h3 className="text-label font-semibold">{t('notice.selfConfirmation.title')}</h3>
      <label className="flex cursor-pointer items-start gap-3">
        <input
          type="checkbox"
          checked={ticked}
          onChange={(event) => setTickedSha(event.target.checked ? head.sha : undefined)}
          className="mt-0.5 size-5 shrink-0 accent-primary"
        />
        <span className="text-body-sm break-words">
          {rich('notice.selfConfirmation.checkbox', {
            repo: repo ?? t('term.sourceRepository.label'),
            shortSha: <Sha sha={head.sha} />,
          })}
        </span>
      </label>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <Button type="button" variant="link" size="compact" className="text-body-sm" onClick={() => setGuidance(true)}>
          {t('screen.job.setup.card.guidance')}
        </Button>
        {setup.sourceUrl && (
          <OutLink href={workflowActionsUrl(setup.sourceUrl)}>{t('screen.product.wizard.selfScan')}</OutLink>
        )}
      </div>
      <p className="text-caption text-muted-foreground">
        {t('notice.selfConfirmation.versionLine', { version: SELF_CONFIRMATION_NOTICE_VERSION })}
      </p>
      <Button type="button" className="w-fit" disabled={!ticked || !repo || confirm.isPending} onClick={submit}>
        {t('screen.job.setup.card.confirm')}
      </Button>
      {noticeChanged && (
        <div role="alert" className="flex flex-col gap-2 text-body-sm">
          <p>{t('screen.job.setup.card.noticeChanged')}</p>
          <Button type="button" variant="secondary" className="w-fit" onClick={() => window.location.reload()}>
            {t('screen.job.setup.card.reload')}
          </Button>
        </div>
      )}
      {problem && (
        <p role="alert" className="text-body-sm text-destructive">
          {problem}
        </p>
      )}
      <Sheet open={guidance} onClose={() => setGuidance(false)} title={t('screen.job.setup.card.guidanceTitle')}>
        {setup.sourceUrl ? (
          <SecretGuidance htmlUrl={setup.sourceUrl} />
        ) : (
          <p className="text-body-sm">{t('notice.product.guidance')}</p>
        )}
      </Sheet>
      <StaleHeadDialog
        open={stale}
        onReload={() => {
          setStale(false)
          void client.invalidateQueries({ queryKey: setupKey(jobId) })
        }}
      />
    </div>
  )
}

/** A `STALE` that carries no head, such as a Job that left Starting: "Thông tin đã thay đổi…" and its reason. */
function staleText(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || error.problem.code !== 'STALE') {
    return undefined
  }
  const key = `reason.repo.${error.problem.reason ?? ''}` as MessageKey
  const reason = t(key)
  return reason === key ? t('error.STALE') : `${t('error.STALE')} ${reason}`
}

/** The Stale dialog (UX-DR26, AD-23): the confirmation was not sent; the card shows the new head to confirm again. */
function StaleHeadDialog({ open, onReload }: { open: boolean; onReload: () => void }) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onReload()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('error.STALE')}</Dialog.Title>
          <Dialog.Description className="text-body-sm">{t('screen.job.setup.card.staleBody')}</Dialog.Description>
          <div className="flex justify-end">
            <Button type="button" onClick={onReload}>
              {t('screen.matching.stale.reload')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/**
 * The copy on the Customer's GitHub Actions as an Actions run status row: waiting for GitHub until the run starts,
 * running until the copy is read back, done once both repositories read back at the confirmed head, or not run, with
 * its named reason, once the Setup is blocked on it. Only the Customer sees the run's link, because the run is in their
 * repository; nobody sees a row before the copy is reached.
 */
function CopyRun({
  setup,
  state,
  customer,
  reason,
  action,
}: {
  setup: Setup
  state: string
  customer: boolean
  reason?: string
  action?: ReactNode
}) {
  const shown = runState(setup, state)
  if (!shown) {
    return null
  }
  return (
    <div className="w-full pl-7">
      <ActionsRunRow
        purpose={t('screen.job.actionsRun.purposeCopy')}
        state={shown}
        htmlUrl={customer ? setup.run?.htmlUrl : undefined}
        reason={shown === 'FAILED' ? reason : undefined}
        action={shown === 'FAILED' ? action : undefined}
      />
    </div>
  )
}

/**
 * The copy run's row state. "Xong" only once the step is done, after the read-back; "Không chạy được" only once the
 * Setup is blocked on the run. While the step is current, a run that completed still reads as running: the read-back,
 * or a retry of a failed run, follows.
 */
function runState(setup: Setup, state: string): RunState | undefined {
  if (state === 'DONE') {
    return 'DONE'
  }
  if (state === 'BLOCKED') {
    return setup.status === 'BLOCKED' && RUN_BLOCKS.includes(setup.blockedReason ?? '') ? 'FAILED' : undefined
  }
  if (state !== 'CURRENT') {
    return undefined
  }
  const run = setup.run
  return !run || run.state === 'DISPATCHED' || run.state === 'QUEUED' ? 'WAITING' : 'RUNNING'
}

/** The states of an invitation the person has accepted, after which the Customer's optional note says nothing new. */
const ACCEPTED_STATES = ['ACCEPTED', 'TWO_FACTOR_MISSING', 'WRITE']

/**
 * A sub-row's name. The Customer's read row (R5, Story 23.7) is their own to them, named by their GitHub username, and
 * reads "Khách hàng" to the Mentor and the Crew, who are never told the Customer's username.
 */
function personName(person: Person, customer: boolean): string {
  if (person.role !== 'CUSTOMER') {
    return person.name
  }
  return customer && person.name
    ? t('screen.job.setup.personCustomerOwn', { login: person.name })
    : t('screen.job.setup.personCustomer')
}

/**
 * UX-DR23's sub-rows: one per person, with their state and, on their own row, the GitHub link. The Customer's row
 * (UX-DR114) shows their read of the Intermediary Repository; on their own row it adds the 2FA note, as every own
 * pending row does, and that the invitation is not required, since it never holds the start (R5).
 */
function People({ people, customer }: { people: Person[]; customer: boolean }) {
  return (
    <ul className="flex w-full flex-col gap-1 pl-7">
      {people.map((person, index) => (
        <li key={`${person.role}-${index}`} className="flex flex-wrap items-center gap-1.5 text-caption">
          <span className="font-semibold break-words">{personName(person, customer)}</span>
          {person.role === 'CUSTOMER' && (
            <span className="text-muted-foreground">· {t('screen.job.setup.customerRead')}</span>
          )}
          <span className="text-muted-foreground">
            ·{' '}
            {t(`screen.job.setup.person${person.state}` as MessageKey, {
              date: person.expiresAt ? formatDayMonth(person.expiresAt) : '',
            })}
          </span>
          {person.githubUrl && (
            <a
              href={person.githubUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
            >
              {t('screen.job.setup.openGitHub')}
              <ExternalLink aria-hidden="true" className="size-3.5" />
            </a>
          )}
          {ownInvited(person) && (
            <p className="flex w-full flex-wrap items-center gap-1.5">
              <span>{t('screen.job.setup.ownInvitedHint')}</span>
              <a
                href={TWO_FACTOR_SETTINGS}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
              >
                {t('screen.job.setup.enableTwoFactor')}
                <ExternalLink aria-hidden="true" className="size-3.5" />
              </a>
              <span className="text-muted-foreground">{t('screen.job.setup.githubDomain')}</span>
            </p>
          )}
          {customer && person.role === 'CUSTOMER' && !ACCEPTED_STATES.includes(person.state) && (
            <p className="w-full text-muted-foreground">{t('screen.job.setup.customerOptional')}</p>
          )}
        </li>
      ))}
    </ul>
  )
}
