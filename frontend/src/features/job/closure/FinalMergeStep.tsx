import { CircleCheck, TriangleAlert } from 'lucide-react'
import { useId, useState } from 'react'
import { TermHint } from '@/components/TermHint'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { ActionsRunRow, type RunState } from '@/features/job/ActionsRunRow'
import { OutLink } from '@/features/product/WorkflowSetup'
import { SensitiveChangeList } from '@/features/delivery/SensitiveChangeList'
import { useConfirmFinalMerge, useFinalMerge, type FinalMerge } from './finalMergeApi'
import { DeclineToZip, ZipFallbackStep } from './ZipFallbackStep'

/** The reasons the closure section words itself; any other reads as the generic failure. */
const REASONS = ['WORKFLOW_FILES_CHANGED', 'ACTIONS_QUOTA', 'EXCHANGE_REFUSED', 'RUN_FAILED', 'BRANCH_EXISTS',
  'J9_GUARD']

/**
 * The Customer's Final Merge in JOB-1's closure section (FR-37, UX-DR35, UX-DR116, Story 23.9a): the Term hint and
 * the FR-37 deadline; then the whole Job's Sensitive change list with N-18 and "Chép mã vào dự án của tôi". That
 * dispatches one run of the published workflow on the Customer's own GitHub Actions, shown in the shared Actions run
 * status row; once vibefix has read the branch back, the section names it and links GitHub's compare page, where the
 * Customer opens and merges the pull request themselves. vibefix writes nothing in their project.
 * <p>
 * When the accepted code changes a file under `.github/workflows/`, N-18's checkbox gives way to its workflow-file
 * state, because a run's own token can never push those files. A run that cannot finish names its reason and keeps
 * the fallback offered. Nothing shows for anyone else, or once the row ended.
 */
export function FinalMergeStep({ jobId }: { jobId: string }) {
  const merge = useFinalMerge(jobId)
  if (!merge.data) {
    return null
  }
  if (merge.data.status === 'ENDED') {
    // A run that could not finish ends the row, so its own named reason stands above the fallback it offers.
    return (
      <div className="flex flex-col gap-3">
        <Failure merge={merge.data.merge} />
        <ZipFallbackStep jobId={jobId} merge={merge.data} />
      </div>
    )
  }
  return <Merge jobId={jobId} merge={merge.data} />
}

/** "Chưa chép được mã vào dự án của bạn: {reason}", with the run's row when one was dispatched. */
function Failure({ merge }: { merge: FinalMerge['merge'] }) {
  if (!merge?.refusal) {
    return null
  }
  return (
    <div className="flex flex-col gap-2">
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
    </div>
  )
}

function Merge({ jobId, merge }: { jobId: string; merge: FinalMerge }) {
  const ids = useId()
  const confirm = useConfirmFinalMerge(jobId)
  const [agreed, setAgreed] = useState(false)
  const [error, setError] = useState<string>()
  const repo = merge.repository
  const step = merge.merge
  const date = merge.deadlineAt ? formatDayMonth(merge.deadlineAt) : t('screen.closure.finalMerge.beforeEnd')
  const heading = (
    <h3 id={`${ids}-final-merge`} className="text-h3">
      <TermHint term="finalMerge" />
    </h3>
  )

  if (step?.status === 'DONE') {
    return (
      <section aria-labelledby={`${ids}-final-merge`} className="flex flex-col gap-2 rounded-md border border-border p-4">
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
      <section aria-labelledby={`${ids}-final-merge`} className="flex flex-col gap-2 rounded-md border border-border p-4">
        {heading}
        {merge.deadlineAt && <p className="text-body-sm">{t('screen.closure.finalMerge.deadline', { date })}</p>}
        {step && (
          <p role="status" className="text-body-sm text-muted-foreground">
            {t('screen.closure.finalMerge.checking')}
          </p>
        )}
        <DeclineToZip jobId={jobId} repo={repo} />
      </section>
    )
  }
  if (step.status === 'CONFIRMED' || step.status === 'RUNNING') {
    return (
      <section aria-labelledby={`${ids}-final-merge`} className="flex flex-col gap-2 rounded-md border border-border p-4">
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
    // A refusal normally ends the row in the same transaction, so this is the moment before the fallback shows.
    return (
      <section aria-labelledby={`${ids}-final-merge`} className="flex flex-col gap-2 rounded-md border border-border p-4">
        {heading}
        <Failure merge={step} />
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
    <section aria-labelledby={`${ids}-final-merge`} className="flex flex-col gap-3 rounded-md border border-border p-4">
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
      <div className="flex flex-wrap gap-3">
        {!blocked && (
          <Button type="button" className="w-fit" disabled={!agreed || confirm.isPending} onClick={run}>
            {t('screen.closure.finalMerge.confirm')}
          </Button>
        )}
        <DeclineToZip jobId={jobId} repo={repo} />
      </div>
    </section>
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
