import { CircleCheck, TriangleAlert } from 'lucide-react'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { TermHint } from '@/components/TermHint'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { ActionsRunRow, type RunState } from '@/features/job/ActionsRunRow'
import { OutLink } from '@/features/product/WorkflowSetup'
import { SensitiveChangeList } from '@/features/delivery/SensitiveChangeList'
import { FINAL_MERGE_ANCHOR } from './ClosingChoice'
import {
  MERGE_CODE_PARAM,
  useConfirmFinalMerge,
  useFinalMerge,
  type FinalMerge,
  type FinalMergeWait,
} from './finalMergeApi'

/** The reasons the closure section words itself; any other reads as the generic failure. */
const REASONS = ['WORKFLOW_FILES_CHANGED', 'ACTIONS_QUOTA', 'EXCHANGE_REFUSED', 'RUN_FAILED', 'BRANCH_EXISTS',
  'J9_GUARD']

/**
 * The Customer's Final Merge in JOB-1's closure section (FR-37, UX-DR35, UX-DR116, Story 23.9a): the Term hint and
 * the FR-37 deadline; then the whole Job's Sensitive change list with N-18 and "Chép mã vào dự án của tôi". That
 * dispatches one run of the published workflow on the Customer's own GitHub Actions, shown in the shared Actions run
 * status row; once vibefix has read the branch back, the section names it and links GitHub's compare page, where the
 * Customer opens and merges the pull request themselves. vibefix writes nothing in their project. While the accepted
 * code is checked, the card says what the check waits for and when it looks again (Story 24.5b).
 * <p>
 * When the accepted code changes a file under `.github/workflows/`, N-18's checkbox gives way to its workflow-file
 * state, because a run's own token can never push those files. A run that cannot finish names its reason here, with
 * the Job's Sensitive Changes and "Đăng công việc ghép mã" under it, and the self-download path stays offered in the
 * Closing choice cards above (Story 23.10a), which also hold the choice's own deadline chip. Nothing shows for
 * anyone but the Customer.
 * <p>
 * Story 23.10c retired the ZIP Fallback: there is no card below this one any more, and no ZIP of vibefix's own to
 * download. A refusal no longer ends the closing choice either, so this card keeps speaking while the FR-37
 * deadline runs on.
 */
export function FinalMergeStep({ jobId }: { jobId: string }) {
  const merge = useFinalMerge(jobId)
  if (!merge.data || merge.data.outcome === 'SELF_DOWNLOAD') {
    // The Customer took the code themselves (Story 23.10a): the Closing choice cards show that outcome alone.
    return null
  }
  if (merge.data.status === 'ENDED') {
    if (!merge.data.merge?.refusal) {
      // The row ended with no Final Merge of its own: a cancellation at J10, or a proposal that ended.
      return null
    }
    // A refusal leaves the row open, so the deadline that ended it came later: the reason still stands, in the
    // same card it stood in while the choice was open.
    return <Ended jobId={jobId} merge={merge.data} />
  }
  return <Merge jobId={jobId} merge={merge.data} />
}

/** The Final Merge card of a row that has ended: its refusal alone, framed as every other state of the card is. */
function Ended({ jobId, merge }: { jobId: string; merge: FinalMerge }) {
  const ids = useId()
  return (
    <section
      id={FINAL_MERGE_ANCHOR}
      aria-labelledby={`${ids}-final-merge`}
      className="flex flex-col gap-3 border-t border-border pt-4"
    >
      <h3 id={`${ids}-final-merge`} className="text-h3">
        <TermHint term="finalMerge" />
      </h3>
      <Failure jobId={jobId} productId={merge.productId} merge={merge.merge} />
    </section>
  )
}

/**
 * "Chưa chép được mã vào dự án của bạn: {reason}", with the run's row when one was dispatched, the Job's Sensitive
 * Changes, and "Đăng công việc ghép mã", which pre-fills CUS-6 (Story 23.10c: it used to sit on the ZIP card).
 * The way forward is the self-download card above; nothing here asks the Customer to run anything.
 */
function Failure({
  jobId,
  productId,
  merge,
}: {
  jobId: string
  productId: string
  merge: FinalMerge['merge']
}) {
  const ids = useId()
  if (!merge?.refusal) {
    return null
  }
  return (
    <div className="flex flex-col gap-3">
      {merge.run && (
        <ActionsRunRow
          purpose={t('screen.job.actionsRun.purposeFinalMerge')}
          state="FAILED"
          htmlUrl={merge.run.htmlUrl ?? undefined}
        />
      )}
      <p role="alert" className="flex items-start gap-2 text-body-sm">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warning" />
        {t('screen.closure.finalMerge.failed', { reason: reason(merge.refusal) })}
      </p>
      <SensitiveChangeList changes={merge.sensitive} headingId={`${ids}-sensitive`} />
      <Link
        to={`/san-pham/${productId}/cong-viec-moi?${MERGE_CODE_PARAM}=${jobId}`}
        className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit no-underline')}
      >
        {t('screen.closure.finalMerge.post')}
      </Link>
    </div>
  )
}

function Merge({ jobId, merge }: { jobId: string; merge: FinalMerge }) {
  const ids = useId()
  const confirm = useConfirmFinalMerge(jobId)
  const [agreed, setAgreed] = useState(false)
  const [error, setError] = useState<string>()
  const step = merge.merge
  const date = merge.deadlineAt ? formatDayMonth(merge.deadlineAt) : t('screen.closure.finalMerge.beforeEnd')
  const heading = (
    <h3 id={`${ids}-final-merge`} className="text-h3">
      <TermHint term="finalMerge" />
    </h3>
  )

  if (step?.status === 'DONE') {
    return (
      <section
        id={FINAL_MERGE_ANCHOR}
        aria-labelledby={`${ids}-final-merge`}
        className="flex flex-col gap-2 border-t border-border pt-4"
      >
        {heading}
        <ActionsRunRow
          purpose={t('screen.job.actionsRun.purposeFinalMerge')}
          state="DONE"
          htmlUrl={step.run?.htmlUrl ?? undefined}
        />
        <p className="flex items-center gap-2 text-body-sm font-semibold">
          <CircleCheck aria-hidden="true" className="size-4 shrink-0 text-success" />
          {t('screen.closure.finalMerge.branchReady', { branch: step.branch ?? '' })}
        </p>
        <p className="text-body-sm">{t('screen.closure.finalMerge.branchReadyLead')}</p>
        <div className="flex flex-col gap-1">
          {step.compareUrl && <OutLink href={step.compareUrl}>{t('screen.closure.finalMerge.openCompare')}</OutLink>}
          {step.branchUrl && <OutLink href={step.branchUrl}>{t('screen.closure.finalMerge.viewBranch')}</OutLink>}
        </div>
      </section>
    )
  }
  if (!step || step.status === 'CHECKING') {
    return (
      <section
        id={FINAL_MERGE_ANCHOR}
        aria-labelledby={`${ids}-final-merge`}
        className="flex flex-col gap-2 border-t border-border pt-4"
      >
        {heading}
        {merge.deadlineAt && <p className="text-body-sm">{t('screen.closure.finalMerge.deadline', { date })}</p>}
        {step && <Checking wait={step.wait} productId={merge.productId} />}
      </section>
    )
  }
  if (step.status === 'CONFIRMED' || step.status === 'RUNNING') {
    return (
      <section
        id={FINAL_MERGE_ANCHOR}
        aria-labelledby={`${ids}-final-merge`}
        className="flex flex-col gap-2 border-t border-border pt-4"
      >
        {heading}
        <ActionsRunRow
          purpose={t('screen.job.actionsRun.purposeFinalMerge')}
          state={runState(step.run?.state)}
          htmlUrl={step.run?.htmlUrl ?? undefined}
        />
      </section>
    )
  }
  if (step.status !== 'READY') {
    // A refusal leaves the closing choice open (Story 23.10c), so this card is where it speaks from now on.
    return (
      <section
        id={FINAL_MERGE_ANCHOR}
        aria-labelledby={`${ids}-final-merge`}
        className="flex flex-col gap-3 border-t border-border pt-4"
      >
        {heading}
        <Failure jobId={jobId} productId={merge.productId} merge={step} />
      </section>
    )
  }
  const blocked = step.workflows.length > 0
  const run = () => {
    setError(undefined)
    confirm.mutate(undefined, {
      onError: (failure) => setError(problemMessage(failure, { module: 'repo' }).text),
    })
  }
  return (
    <section
      id={FINAL_MERGE_ANCHOR}
      aria-labelledby={`${ids}-final-merge`}
      className="flex flex-col gap-3 border-t border-border pt-4"
    >
      {heading}
      {merge.deadlineAt && <p className="text-body-sm">{t('screen.closure.finalMerge.deadline', { date })}</p>}
      <p id={`${ids}-review`} className="text-body-sm">
        {t('screen.closure.finalMerge.reviewLead')}
      </p>
      {blocked && (
        <div role="note" className="flex flex-col gap-1 rounded-md border-l-4 border-warning bg-warning-soft p-3 text-body-sm">
          <p className="flex items-center gap-2 font-semibold">
            <TriangleAlert aria-hidden="true" className="size-4 shrink-0 text-warning" />
            {t('screen.closure.finalMerge.workflowsTitle')}
          </p>
          <ul className="flex flex-col gap-0.5">
            {step.workflows.map((path) => (
              <li key={path}>
                <code className="font-mono text-caption break-all">{path}</code>
              </li>
            ))}
          </ul>
          <p>{t('screen.closure.finalMerge.workflowsLead')}</p>
        </div>
      )}
      <SensitiveChangeList changes={step.sensitive} headingId={`${ids}-sensitive`} />
      {!blocked && (
        <label className="flex items-start gap-2 text-body-sm">
          <input
            type="checkbox"
            className="mt-1 size-4"
            checked={agreed}
            onChange={(event) => setAgreed(event.target.checked)}
          />
          {t('screen.closure.finalMerge.agree')}
        </label>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {!blocked && (
        <div className="flex flex-wrap gap-3">
          <Button type="button" className="w-fit" disabled={!agreed || confirm.isPending} onClick={run}>
            {t('screen.closure.finalMerge.confirm')}
          </Button>
        </div>
      )}
    </section>
  )
}

/**
 * What the check of the accepted code waits for, and when it looks again (Story 24.5b): the team's access to be
 * revoked on GitHub, the Customer's fix of their project, which "Mở trang sản phẩm" opens on CUS-4, or a dispute.
 * CUS-4 shows the block itself, so none is named here. With no wait, the code is being checked now.
 */
function Checking({ wait, productId }: { wait?: FinalMergeWait | null; productId: string }) {
  if (!wait) {
    return (
      <p role="status" className="text-body-sm text-muted-foreground">
        {t('screen.closure.finalMerge.checking')}
      </p>
    )
  }
  const next = wait.nextCheckAt
    ? t('screen.closure.finalMerge.wait.recheckAt', { time: formatDayMonthTime(wait.nextCheckAt) })
    : wait.reason === 'REVOCATION'
      ? t('screen.closure.finalMerge.wait.afterRevocation')
      : undefined
  return (
    <div className="flex flex-col gap-1">
      <p role="status" className="text-body-sm text-muted-foreground">
        {t(`screen.closure.finalMerge.wait.${wait.reason}`)}
        {next && ` ${next}`}
      </p>
      {wait.reason === 'CUSTOMER' && (
        <Link to={`/san-pham/${productId}`} className="w-fit text-body-sm text-primary underline-offset-4 hover:underline">
          {t('screen.closure.finalMerge.wait.openProduct')}
        </Link>
      )}
    </div>
  )
}

/** The run row's state while the Final Merge is out: waiting for GitHub until it starts, then running. */
function runState(state?: string | null): RunState {
  return !state || state === 'DISPATCHED' || state === 'QUEUED' ? 'WAITING' : 'RUNNING'
}

/** The plain reason a Final Merge that could not run shows; one the section does not word reads generically. */
function reason(refusal?: string | null): string {
  return refusal && REASONS.includes(refusal)
    ? t(`screen.closure.finalMerge.reason.${refusal}` as MessageKey)
    : t('screen.closure.finalMerge.reason.OTHER')
}
