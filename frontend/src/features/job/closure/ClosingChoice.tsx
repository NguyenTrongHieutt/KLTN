import { CircleCheck } from 'lucide-react'
import { useId, useState } from 'react'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { TermHint } from '@/components/TermHint'
import { Button } from '@/components/ui/button'
import { DeadlineChip } from '@/features/notification/DeadlineChip'
import { OutLink } from '@/features/product/WorkflowSetup'
import { t } from '@/i18n/t'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { useConfirmCodeReceipt, useFinalMerge, type FinalMerge } from './finalMergeApi'

/** The anchor the Final Merge card points at: the Final Merge's own states, right below the cards. */
export const FINAL_MERGE_ANCHOR = 'hop-nhat-cuoi'

/**
 * The Closing choice cards on JOB-1 (FR-37, FR-38, D10, UX-DR116, Story 23.10a). From J8 the Customer reads two
 * cards under the Deadline chip "Chọn trước {date}", neither preselected and neither recommended: "Hợp nhất vào dự
 * án của bạn" first, which leads into the Final Merge's own step below, and "Tôi đã tự tải / clone từ GitHub", which
 * links the Intermediary Repository on GitHub, gives numbered plain steps for GitHub's own "Download ZIP" or a clone,
 * and asks the Customer to run nothing (FR-38). Confirming the second one records the code receipt and ends the Job.
 * <p>
 * Picking one does not close the other: the self-download stays while the Final Merge is dispatched, running, or
 * failed with its reason, and the Final Merge stays until the receipt is recorded; the server says which is open.
 * Once the branch is read back or the receipt recorded, the outcome stands alone. A Job Cancelled with accepted work
 * keeps the self-download card only, until its repositories are deleted.
 * <p>
 * Story 23.10b: a deadline that passed with neither path taken ends the Job anyway, and the Customer reads that
 * above the self-download card, with the date the repositories are deleted; the card stays open until then.
 * <p>
 * The Mentor reads one line instead -- the Customer's choice with its deadline, then the outcome, which may be that
 * the Customer never confirmed -- and never the Customer's own actions, link or date.
 */
export function ClosingChoice({ jobId, version }: { jobId: string; version: number }) {
  const view = useFinalMerge(jobId)
  if (!view.data) {
    return null
  }
  return view.data.audience === 'MENTOR' ? (
    <MentorLine view={view.data} />
  ) : (
    <Cards jobId={jobId} version={version} view={view.data} />
  )
}

/**
 * "Khách hàng đang chọn cách nhận mã (hạn {date})" while the choice really is open, then the outcome -- the code
 * merged, taken by the Customer, or, since Story 23.10b, never confirmed as received -- then nothing: a Job that
 * ended another way, such as by the closure fallback, has no choice left to report and no outcome of its own.
 * Read-only: no link, no action and no date of the Customer's own.
 */
function MentorLine({ view }: { view: FinalMerge }) {
  const outcome = view.outcome
  // The server reports the deadline only while the Job is Closing, which is while the choice is the Customer's to make.
  const deadline = view.choice.deadlineAt
  const choosing = deadline != null && (view.choice.finalMerge || view.choice.selfDownload)
  if (!outcome && !choosing) {
    return null
  }
  return (
    <p className="text-body-sm text-muted-foreground">
      {outcome === 'MERGED'
        ? t('screen.closure.choice.mentor.MERGED')
        : outcome === 'SELF_DOWNLOAD'
          ? t('screen.closure.choice.mentor.SELF_DOWNLOAD')
          : outcome === 'UNCONFIRMED'
            ? t('screen.closure.choice.mentor.UNCONFIRMED')
            : t('screen.closure.choice.mentor.open', { date: formatDayMonth(deadline ?? '') })}
    </p>
  )
}

function Cards({ jobId, version, view }: { jobId: string; version: number; view: FinalMerge }) {
  const ids = useId()
  const choice = view.choice
  const receipt = view.receipt
  if (receipt) {
    return (
      <p className="flex items-center gap-2 text-body-sm font-semibold">
        <CircleCheck aria-hidden="true" className="size-4 shrink-0 text-success" />
        {t('screen.closure.choice.done', { time: formatDayMonthTime(receipt.confirmedAt) })}
      </p>
    )
  }
  // Story 23.10b: the deadline passed with neither path taken, so there is no choice left to offer but there is
  // still something to say -- and, while the repositories stand, still something to download.
  const unconfirmed = view.outcome === 'UNCONFIRMED'
  if (!unconfirmed && !choice.finalMerge && !choice.selfDownload) {
    return null
  }
  return (
    <section aria-labelledby={`${ids}-choice`} className="flex flex-col gap-3">
      {unconfirmed ? (
        <div className="flex flex-col gap-1">
          <h3 id={`${ids}-choice`} className="text-h3">
            {t('screen.closure.choice.unconfirmed.title')}
          </h3>
          <p className="text-body-sm">
            {/* Nothing left to download means the repositories are gone: a date to fetch by would read as a promise. */}
            {!choice.selfDownload
              ? t('screen.closure.choice.unconfirmed.leadDeleted')
              : choice.deletionAt
                ? t('screen.closure.choice.unconfirmed.lead', { date: formatDayMonth(choice.deletionAt) })
                : t('screen.closure.choice.unconfirmed.leadUntilDeletion')}
          </p>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          <h3 id={`${ids}-choice`} className="text-h3">
            <TermHint term="closingChoice" />
          </h3>
          {choice.deadlineAt && (
            <DeadlineChip
              deadlineAt={choice.deadlineAt}
              near={choice.near}
              text={t('screen.closure.choice.deadline', { date: formatDayMonth(choice.deadlineAt) })}
            />
          )}
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-2">
        {choice.finalMerge && (
          <div className="flex flex-col gap-2 rounded-md border border-border p-4">
            <h4 className="text-label">
              <TermHint term="finalMerge" />
            </h4>
            <p className="text-body-sm">
              {t('screen.closure.choice.finalMerge.lead', { repo: view.repository })}
            </p>
            <a
              href={`#${FINAL_MERGE_ANCHOR}`}
              className="w-fit text-body-sm text-primary underline-offset-4 hover:underline"
            >
              {t('screen.closure.choice.finalMerge.action')}
            </a>
          </div>
        )}
        {choice.selfDownload && <SelfDownload jobId={jobId} version={version} choice={choice} />}
      </div>
    </section>
  )
}

/**
 * "Tôi đã tự tải / clone từ GitHub": the link to the Intermediary Repository, the numbered steps for GitHub's own
 * "Download ZIP" or a clone, and the Confirmation dialog that records the receipt. Nothing here asks for a command.
 */
function SelfDownload({
  jobId,
  version,
  choice,
}: {
  jobId: string
  version: number
  choice: FinalMerge['choice']
}) {
  const confirm = useConfirmCodeReceipt(jobId, version)
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<string>()
  const steps = ['step1', 'step2', 'step3', 'step4'] as const
  return (
    <div className="flex flex-col gap-2 rounded-md border border-border p-4">
      {/* The Plain-Term List's own label, as plain text: the action below carries the same words, and two buttons
          of one name would read alike to a screen reader. */}
      <h4 className="text-label">{t('term.selfDownload.label')}</h4>
      <p className="text-body-sm">{t('screen.closure.choice.selfDownload.lead')}</p>
      {choice.intermediaryUrl && <OutLink href={choice.intermediaryUrl}>{t('screen.closure.choice.selfDownload.open')}</OutLink>}
      <p className="text-body-sm font-semibold">{t('screen.closure.choice.selfDownload.stepsTitle')}</p>
      <ol className="flex list-decimal flex-col gap-1 ps-5 text-body-sm">
        {steps.map((step) => (
          <li key={step}>{t(`screen.closure.choice.selfDownload.${step}`)}</li>
        ))}
      </ol>
      <p className="text-caption text-muted-foreground">{t('screen.closure.choice.selfDownload.noRun')}</p>
      <p className="text-caption text-muted-foreground">{t('screen.closure.choice.selfDownload.invitation')}</p>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <Button type="button" variant="secondary" className="w-fit" onClick={() => setAsking(true)}>
        {t('screen.closure.choice.selfDownload.action')}
      </Button>
      <ConfirmDialog
        open={asking}
        title={t('screen.closure.choice.dialogTitle')}
        body={
          choice.deletionAt
            ? t('screen.closure.choice.dialogBody', { date: formatDayMonth(choice.deletionAt) })
            : t('screen.closure.choice.dialogBodyUntilDeletion')
        }
        confirmLabel={t('screen.closure.choice.selfDownload.action')}
        pending={confirm.isPending}
        onClose={() => setAsking(false)}
        onConfirm={() => {
          setError(undefined)
          confirm.mutate(undefined, {
            onSuccess: () => setAsking(false),
            onError: (failure) => {
              setAsking(false)
              setError(problemMessage(failure, { module: 'repo' }).text)
            },
          })
        }}
      />
    </div>
  )
}
