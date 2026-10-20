import { CircleCheck, MessageSquare } from 'lucide-react'
import { useState } from 'react'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import type { CrewPanel } from '@/features/crew/crewApi'
import type { MentorJob } from '@/features/matching/mentorJobApi'
import { ConfirmDialog } from '@/features/team/ConfirmDialog'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { AgreementContentView } from './AgreementContent'
import { useConfirmAgreement, type AgreementContent, type AgreementDraft, type ConfirmedAgreement } from './agreementApi'
import { inOrder, targetLabel } from './reviewTargets'

/**
 * What the Customer said about the latest shared version, at the top of the Mentor's editor
 * (FR-16): each comment with the item it is about, the change request's note, and its proposed
 * Milestone order with "Áp dụng thứ tự".
 */
export function CustomerFeedback({
  review,
  content,
  onApplyOrder,
}: {
  review: AgreementDraft['review']
  content: AgreementContent
  onApplyOrder: (order: string[]) => void
}) {
  if (review.latestVersionNo == null) {
    return null
  }
  const request = review.changeRequest
  const order = request?.milestoneOrder
  return (
    <section aria-labelledby="feedback-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id="feedback-heading" className="flex items-center gap-2 text-h2">
        <MessageSquare aria-hidden="true" className="size-5 text-primary" />
        {t('screen.agreement.review.reviewTitle', { n: review.latestVersionNo })}
      </h2>
      {request && (
        <div className="flex flex-col gap-2 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
          <p className="font-semibold">
            {t('screen.agreement.review.changeTitle', { n: request.versionNo, time: formatDayMonthTime(request.createdAt) })}
          </p>
          <p className="break-words whitespace-pre-line">{request.note}</p>
          {order && (
            <>
              <p>{t('screen.agreement.review.proposedOrder')}</p>
              <ol className="list-decimal pl-5">
                {inOrder(content.milestones, order).map((milestone) => (
                  <li key={milestone.id}>{milestone.title}</li>
                ))}
              </ol>
              <Button type="button" variant="secondary" size="compact" className="self-start" onClick={() => onApplyOrder(order)}>
                {t('screen.agreement.review.applyOrder')}
              </Button>
            </>
          )}
        </div>
      )}
      {review.comments.length === 0 ? (
        <p className="text-body-sm text-muted-foreground">{t('screen.agreement.review.noComments')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {review.comments.map((comment) => (
            <li key={comment.id} className="flex flex-col gap-0.5 rounded-md bg-muted px-3 py-2 text-body-sm">
              <span className="text-caption text-muted-foreground">
                {targetLabel(content, comment.targetKind, comment.targetId)} ·{' '}
                {t('screen.agreement.review.customerComment', { time: formatDayMonthTime(comment.createdAt) })}
              </span>
              <span className="break-words whitespace-pre-line">{comment.body}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/**
 * "Xác nhận thỏa thuận" (FR-17): enabled once the Customer approved the latest shared version, the
 * live draft says the same, and the Crew is confirmed; the command carries the `Job.version` and the
 * `crewVersion` the screen loaded.
 */
export function ConfirmCard({ job, draft, panel }: { job: MentorJob; draft: AgreementDraft; panel: CrewPanel }) {
  const confirm = useConfirmAgreement(job.id)
  const toasts = useToasts()
  const [asking, setAsking] = useState(false)
  const review = draft.review
  const reasons: MessageKey[] = []
  if (review.latestVersionNo == null) {
    reasons.push('screen.agreement.review.whyNotShared')
  } else {
    if (!review.approvedAt) {
      reasons.push('screen.agreement.review.whyNotApproved')
    }
    if (!review.draftMatchesShared) {
      reasons.push('screen.agreement.review.whyDraft')
    }
  }
  if (!panel.crewConfirmed) {
    reasons.push('screen.agreement.review.whyCrew')
  }
  const submit = () =>
    confirm.mutate(
      { version: job.version, crewVersion: panel.crewVersion },
      {
        onSuccess: () => {
          setAsking(false)
          toasts.success(t('screen.agreement.review.confirmed'))
        },
        onError: (error) => {
          setAsking(false)
          toasts.error(
            error instanceof ApiError && error.problem.code === 'STALE'
              ? t('error.STALE')
              : problemMessage(error, { module: 'job', action: t('screen.agreement.review.confirmAction') }).text,
          )
        },
      },
    )
  return (
    <section aria-labelledby="confirm-heading" className="flex w-full max-w-[1120px] flex-col gap-3 self-center rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="confirm-heading" className="text-h3">
          {t('screen.agreement.review.confirmTitle')}
        </h2>
        <span className="text-caption text-muted-foreground">{t('screen.agreement.review.confirmLead')}</span>
      </div>
      {review.latestVersionNo != null && (
        <p className="text-body-sm">
          {review.approvedAt
            ? t('screen.agreement.review.customerApproved', { n: review.latestVersionNo, time: formatDayMonthTime(review.approvedAt) })
            : t('screen.agreement.review.customerWaiting', { n: review.latestVersionNo })}
        </p>
      )}
      <Button
        type="button"
        className="self-start"
        disabled={reasons.length > 0 || confirm.isPending}
        aria-describedby="confirm-why"
        onClick={() => setAsking(true)}
      >
        {t('screen.agreement.review.confirm')}
      </Button>
      {reasons.length > 0 && (
        <p id="confirm-why" className="text-caption text-muted-foreground">
          {t('screen.agreement.review.confirmWhy', {
            reasons: reasons.map((reason) => t(reason)).join(t('screen.agreement.review.whyJoin')),
          })}
        </p>
      )}
      <ConfirmDialog
        open={asking}
        title={t('screen.agreement.review.confirmDialogTitle')}
        body={t('screen.agreement.review.confirmDialogBody')}
        confirmLabel={t('screen.agreement.review.confirm')}
        pending={confirm.isPending}
        onConfirm={submit}
        onClose={() => setAsking(false)}
      />
    </section>
  )
}

/** The confirmed Agreement, read-only, in place of the editor (FR-17, AD-29). */
export function ConfirmedView({ confirmed }: { confirmed: ConfirmedAgreement }) {
  return (
    <div className="flex w-full max-w-[860px] flex-col gap-5 self-center">
      <section role="status" className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4 text-body-sm">
        <p className="flex items-center gap-2 text-h3">
          <CircleCheck aria-hidden="true" className="size-5 text-success" />
          {t('screen.agreement.review.confirmedTitle', { n: confirmed.versionNo })}
        </p>
        <p>{t('screen.agreement.review.confirmedLine')}</p>
        <p className="text-caption text-muted-foreground">
          {t('screen.agreement.review.confirmedMeta', {
            draft: confirmed.draftVersionNo,
            approved: formatDayMonthTime(confirmed.approvedAt),
            confirmed: formatDayMonthTime(confirmed.confirmedAt),
          })}
        </p>
      </section>
      <AgreementContentView
        content={confirmed.content}
        review={{
          jobId: '',
          versionNo: confirmed.draftVersionNo,
          comments: [],
          canComment: false,
          live: new Set(confirmed.liveAccessIds),
        }}
      />
    </div>
  )
}
