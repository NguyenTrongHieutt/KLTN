import { ArrowDown, ArrowUp, MessageSquare } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { TestTag } from '@/components/TestTag'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDate, formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useCommentOnAgreement, type AgreementComment, type AgreementContent, type CommentTarget } from './agreementApi'
import { stepCount } from './agreementChecks'


export function CodeRights() {
  return (
    <section className="flex flex-col gap-1 rounded-lg bg-muted p-4">
      <p className="text-label font-semibold">{t('screen.agreement.codeRightsTitle')}</p>
      <p className="text-body-sm">{t('screen.agreement.codeRights')}</p>
    </section>
  )
}

type AgreementMilestone = AgreementContent['milestones'][number]

/** What the Customer may do on the version shown (FR-16): comment, reorder, mark live systems. */
export interface ReviewControls {
  jobId: string
  versionNo: number
  comments: AgreementComment[]
  /** The latest version before the confirmation: comments can be added. */
  canComment: boolean
  /** "Lên"/"Xuống", while the Customer may still ask for changes. */
  onMove?: (index: number, delta: -1 | 1) => void
  /** The External Access entries marked as live systems. */
  live: Set<string>
  /** "Đây là hệ thống thật đang dùng", before the approval. */
  onLive?: (id: string, live: boolean) => void
}

/**
 * A shared or confirmed version, read-only (FR-16, UX-DR34): the scope, the Milestones in the order
 * given with each criterion's "Cách kiểm tra" steps, External Access, and the code rights; with
 * `review`, the Customer's comments and choices beside each item.
 */
export function AgreementContentView({
  content,
  quotePrice,
  milestones = content.milestones,
  review,
}: {
  content: AgreementContent
  quotePrice?: number
  milestones?: AgreementMilestone[]
  review?: ReviewControls
}) {
  const total = milestones.reduce((sum, milestone) => sum + (milestone.priceVnd ?? 0), 0)
  return (
    <>
      {!content.assessment && (
        <section className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
          <Commentable review={review} kind="SCOPE" label={t('screen.agreement.review.target.SCOPE')}>
            <h2 className="text-h2">{t('screen.agreement.scope')}</h2>
          </Commentable>
          <p className="text-body-sm break-words whitespace-pre-line">{content.scope}</p>
          {content.codeJob && <p className="text-body-sm text-muted-foreground">{t('screen.agreement.codeStatement')}</p>}
          {content.skills.length > 0 && (
            <ul className="flex flex-wrap gap-1.5">
              {content.skills.map((skill) => (
                <li key={skill.id} className="rounded-full bg-muted px-2 py-0.5 text-caption">
                  {skill.name}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      <section className="flex flex-col gap-3">
        <h2 className="text-h2">{t('screen.agreement.milestones')}</h2>
        {milestones.map((milestone, m) => (
          <MilestoneView key={milestone.id} milestone={milestone} index={m} count={milestones.length} review={review} />
        ))}
        <p className="text-body-sm">
          {t('screen.agreement.total', { total: formatVnd(total) })} <TestTag />
          {quotePrice !== undefined && (
            <>
              {' · '}
              <span className={cn(total !== quotePrice && 'font-semibold text-warning')}>
                {t(total === quotePrice ? 'screen.agreement.matchesQuote' : 'screen.agreement.differsQuote')}
              </span>
            </>
          )}
        </p>
      </section>
      {content.externalAccess.length > 0 && (
        <section className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
          <h2 className="text-h2">{t('screen.agreement.externalAccess')}</h2>
          <ul className="flex flex-col gap-3 text-body-sm">
            {content.externalAccess.map((entry) => {
              const live = entry.environment === 'PRODUCTION' || review?.live.has(entry.id)
              return (
                <li key={entry.id} className="flex flex-col gap-1.5">
                  <Commentable
                    review={review}
                    kind="EXTERNAL_ACCESS"
                    targetId={entry.id}
                    label={t('screen.agreement.review.target.EXTERNAL_ACCESS', { system: entry.system })}
                  >
                    <span className="flex min-w-0 grow flex-wrap items-center justify-between gap-2">
                      <span className="break-words">
                        {entry.system} · {entry.purpose}
                      </span>
                      <span
                        className={cn(
                          'rounded-full px-2 py-0.5 text-caption font-semibold',
                          live ? 'bg-destructive-soft text-destructive' : 'bg-muted',
                        )}
                      >
                        {t(live ? 'screen.agreement.envPRODUCTION' : 'screen.agreement.envNON_PRODUCTION')}
                      </span>
                    </span>
                  </Commentable>
                  {review?.onLive && entry.environment === 'NON_PRODUCTION' && (
                    <label className="flex items-center gap-2 text-body-sm">
                      <input
                        type="checkbox"
                        checked={review.live.has(entry.id)}
                        onChange={(event) => review.onLive?.(entry.id, event.target.checked)}
                      />
                      {t('screen.agreement.review.liveToggle')}
                    </label>
                  )}
                </li>
              )
            })}
          </ul>
        </section>
      )}
      <CodeRights />
    </>
  )
}

function MilestoneView({
  milestone,
  index,
  count,
  review,
}: {
  milestone: AgreementMilestone
  index: number
  count: number
  review?: ReviewControls
}) {
  const label = t('screen.agreement.review.target.MILESTONE', { n: index + 1, title: milestone.title })
  return (
    <article className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
      <Commentable
        review={review}
        kind="MILESTONE"
        targetId={milestone.id}
        label={label}
        actions={
          review?.onMove && (
            <>
              <Button
                type="button"
                variant="ghost"
                size="compact"
                disabled={index === 0}
                aria-label={t('screen.agreement.review.upLabel', { item: label })}
                onClick={() => review.onMove?.(index, -1)}
              >
                <ArrowUp aria-hidden="true" className="size-4" />
                {t('screen.agreement.review.up')}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="compact"
                disabled={index === count - 1}
                aria-label={t('screen.agreement.review.downLabel', { item: label })}
                onClick={() => review.onMove?.(index, 1)}
              >
                <ArrowDown aria-hidden="true" className="size-4" />
                {t('screen.agreement.review.down')}
              </Button>
            </>
          )
        }
      >
        <h3 className="text-h3 break-words">
          {t('screen.agreement.milestone', { n: index + 1 })} · {milestone.title}
        </h3>
      </Commentable>
      <p className="flex flex-wrap gap-4 text-body-sm">
        {milestone.priceVnd != null && (
          <span>
            {t('screen.agreement.priceLine', { price: formatVnd(milestone.priceVnd) })} <TestTag />
          </span>
        )}
        {milestone.dueOn && <span>{t('screen.agreement.due', { date: formatCalendarDate(milestone.dueOn) })}</span>}
      </p>
      <p className="text-caption text-muted-foreground">{t('screen.agreement.roundsCount', { n: milestone.revisionRounds })}</p>
      <p className="text-label font-semibold">{t('screen.agreement.criteriaTitle')}</p>
      <ol className="flex list-decimal flex-col gap-2 pl-5 text-body-sm">
        {milestone.criteria.map((criterion, c) => (
          <li key={criterion.id} className="break-words">
            <Commentable
              review={review}
              kind="CRITERION"
              targetId={criterion.id}
              label={t('screen.agreement.review.target.CRITERION', { c: c + 1, n: index + 1 })}
              small
            >
              <span>
                {criterion.statement}{' '}
                <span className="text-caption text-muted-foreground">
                  · {t('screen.agreement.stepsCount', { n: stepCount(criterion.checkSteps) })}
                </span>
              </span>
            </Commentable>
            {criterion.checkSteps && (
              <p className="mt-1 rounded-md bg-muted px-3 py-2 text-caption whitespace-pre-line">{criterion.checkSteps}</p>
            )}
          </li>
        ))}
      </ol>
    </article>
  )
}

/**
 * An item with its comments: the item's heading, its actions and "Góp ý", the comments made on it,
 * and the comment form once opened.
 */
function Commentable({
  review,
  kind,
  targetId,
  label,
  actions,
  small = false,
  children,
}: {
  review?: ReviewControls
  kind: CommentTarget
  targetId?: string
  label: string
  actions?: ReactNode
  small?: boolean
  children: ReactNode
}) {
  const [open, setOpen] = useState(false)
  const comments = review?.comments.filter((comment) => comment.targetKind === kind && (comment.targetId ?? undefined) === targetId) ?? []
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        {children}
        {(actions || review?.canComment) && (
          <span className="flex flex-wrap items-center gap-1">
            {actions}
            {review?.canComment && (
              <Button
                type="button"
                variant={small ? 'ghost' : 'secondary'}
                size="compact"
                aria-label={t('screen.agreement.review.commentOn', { item: label })}
                aria-expanded={open}
                onClick={() => setOpen(!open)}
              >
                <MessageSquare aria-hidden="true" className="size-4" />
                {t('screen.agreement.review.comment')}
              </Button>
            )}
          </span>
        )}
      </div>
      {comments.map((comment) => (
        <p key={comment.id} className="rounded-md border-l-4 border-primary bg-primary-soft px-3 py-2 text-body-sm">
          <span className="block text-caption text-muted-foreground">
            {t('screen.agreement.review.yourComment', { time: formatDayMonthTime(comment.createdAt) })}
          </span>
          <span className="break-words whitespace-pre-line">{comment.body}</span>
        </p>
      ))}
      {open && review && (
        <CommentForm review={review} kind={kind} targetId={targetId} label={label} onDone={() => setOpen(false)} />
      )}
    </div>
  )
}

function CommentForm({
  review,
  kind,
  targetId,
  label,
  onDone,
}: {
  review: ReviewControls
  kind: CommentTarget
  targetId?: string
  label: string
  onDone: () => void
}) {
  const ids = useId()
  const [body, setBody] = useState('')
  const send = useCommentOnAgreement(review.jobId)
  const toasts = useToasts()
  const submit = () =>
    send.mutate(
      { versionNo: review.versionNo, targetKind: kind, targetId, body: body.trim() },
      {
        onSuccess: () => {
          toasts.success(t('screen.agreement.review.commentSent'))
          onDone()
        },
        onError: (error) =>
          toasts.error(
            error instanceof ApiError && error.problem.code === 'STALE'
              ? t('error.STALE')
              : problemMessage(error, { module: 'job' }).text,
          ),
      },
    )
  return (
    <div className="flex flex-col gap-2 rounded-md border border-border bg-background p-3">
      <label htmlFor={`${ids}-body`} className="text-label">
        {t('screen.agreement.review.commentOn', { item: label })}
      </label>
      <textarea
        id={`${ids}-body`}
        value={body}
        maxLength={1000}
        onChange={(event) => setBody(event.target.value)}
        className={cn(inputClass, 'min-h-18')}
      />
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="ghost" size="compact" onClick={onDone}>
          {t('screen.agreement.review.commentCancel')}
        </Button>
        <Button type="button" size="compact" disabled={body.trim() === '' || send.isPending} onClick={submit}>
          {t('screen.agreement.review.commentSend')}
        </Button>
      </div>
    </div>
  )
}

