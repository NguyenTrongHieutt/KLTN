import { LoaderCircle } from 'lucide-react'
import { ActionsRunRow, type RunState } from '@/features/job/ActionsRunRow'
import { t } from '@/i18n/t'
import type { HandoverSubmission } from './deliveryApi'

/**
 * Where the copy run of a pending submission stands, as the status row's badge reads it (Story 23.8b): nothing
 * dispatched or queued yet is "Đang chờ GitHub", a run in progress "Đang chạy", and a run GitHub concluded as
 * anything but a success "Không chạy được", which is what the Mentor and the Crew see through the retry window
 * before the copy resolves. A submission that is no longer pending shows no banner at all, so its own states are
 * not read here.
 */
function copyRunState(submission: HandoverSubmission): RunState {
  if (submission.runStatus === 'IN_PROGRESS') {
    return 'RUNNING'
  }
  if (submission.runStatus === 'COMPLETED') {
    return submission.runConclusion === 'success' ? 'DONE' : 'FAILED'
  }
  return 'WAITING'
}

/**
 * The handover being copied on the runner (UX-DR115, AD-46, Story 23.8b), for the Mentor and the Crew on JOB-5
 * and on the form that sent it: the Busy banner's visual, which is no busy marker, with
 * "Đang sao chép bàn giao Giai đoạn {n}…" and Story 23.6c's Actions run status row under it. The row carries no
 * run link: a run on the runner is linked for an Administrator alone. The Customer sees none of it until the pull
 * request is open. Its surface polls the submission, and words a refused copy itself once it is no longer pending.
 */
export function CopyRunBanner({ submission }: { submission: HandoverSubmission }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex w-full flex-col gap-2 rounded-md border-l-4 border-primary bg-primary-soft p-3"
    >
      <p className="flex items-center gap-2 text-body-sm">
        <LoaderCircle
          aria-hidden="true"
          className="size-4 shrink-0 animate-spin text-primary motion-reduce:animate-none"
        />
        {t('screen.handover.form.copying', { n: submission.number })}
      </p>
      <ActionsRunRow
        purpose={t('screen.handover.copyPurpose', { n: submission.number })}
        state={copyRunState(submission)}
      />
    </div>
  )
}
