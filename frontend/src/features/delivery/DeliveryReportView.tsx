import { CircleCheck, CircleX } from 'lucide-react'
import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate, formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { cardSurface } from '@/components/ui/card-surface'
import { cn } from '@/lib/utils'
import type { DeliveryReport } from './deliveryApi'
import { HandoverLinkList } from './HandoverLinkList'
import { OutsideLink } from './OutsideLink'
import { StatusBadge } from '@/components/ui/status-badge'
import { handoverTone } from './handoverTone'
import { SensitiveChangeList } from './SensitiveChangeList'
import { Card } from '@/components/ui/card'
import { Alert } from '@/components/ui/alert'

/**
 * The Delivery Report (FR-10, FR-32, AD-33, UX-DR53): per criterion the Mentor's own statement
 * "Người hướng dẫn tự nhận xét: Đạt / Chưa đạt", never counted as a criterion done, their note, and
 * "Cách kiểm tra". Beside them, what the handover carries: the preview or why there is none, the Mentor's links
 * with their kind, label and domain, and the images (Story 24.3). An Assessment Job's criteria are the Assessment
 * Report's sections, and the report itself is its "Tài liệu" link (Stories 24.1, 24.3). A Code Job's report adds the
 * Sensitive change list and the collapsed "Chi tiết kỹ thuật"
 * with the commits, all rendered as text (NFR-3). While it waits for the Customer, they get "Kiểm tra
 * từng tiêu chí" and the Revision Rounds left; once decided, everyone sees the Customer's marks. A Code
 * Job's acceptance says while it merges, and when the merge did not go through (FR-33). A handover voided at
 * acceptance says why, and tells the Customer the Milestone's money is still held (M3). Above the Customer's
 * decision, a handover that holds the Mentor's N-33 confirmation quotes it: vibefix scans no team code, and the
 * Mentor confirmed this one holds none (FR-33, R28, Story 23.8b). A handover recorded before that story holds no
 * confirmation, so it says nothing, and an erased Mentor is named by the word for a deleted account.
 *
 * In a Self-mentored Job a Crew member made the handover (UX-DR126, Story 25.5): the report says "Bàn giao bởi
 * {name}" and "Thành viên tự nhận xét", and N-33's line names the member. A handover the Customer returned (FR-86)
 * says when and shows the reason as written.
 */
export function DeliveryReportView({ report }: { report: DeliveryReport }) {
  const customer = report.audience === 'CUSTOMER'
  const checks = new Map(report.checks.map((check) => [check.criterionId, check]))
  return (
    <article aria-labelledby="delivery-report" className="flex flex-col gap-5">
      <header className="flex flex-col gap-1">
        <h2 id="delivery-report" className="text-h2 break-words">
          {t('screen.handover.report.title', { n: report.number, title: report.milestoneTitle })}
        </h2>
        <p className="text-body-sm text-muted-foreground">
          {/* UX-DR126: a Self-mentored Job's handover names the member who made it. */}
          {t(report.byMember ? 'screen.handover.report.metaMember' : 'screen.handover.report.meta', {
            mentor: report.mentorName || t('screen.split.erased'),
            date: formatDate(report.createdAt),
            round: report.round,
          })}
          {/* FR-87, UX-DR129: how many days after the due date in force this handover came; a record, for every reader. */}
          {report.daysLate != null && report.daysLate > 0 && (
            <>
              {' · '}
              {t('screen.handover.report.daysLate', { n: report.daysLate })}
            </>
          )}
        </p>
        <StatusBadge tone={handoverTone(report.status, report.auto, customer)}>
          {report.auto
            ? t('screen.handover.status.auto')
            : report.returned
              ? t('screen.handover.status.RETURNED')
              : customer && report.status === 'SUBMITTED'
                ? t('screen.handover.status.customer', { date: formatDayMonth(report.checkBy) })
                : t(`screen.handover.status.${report.status}` as MessageKey)}
        </StatusBadge>
      </header>
      {report.acceptance === 'MERGING' && (
        <p role="status" className="rounded-md border-l-4 border-info bg-info-soft p-4 text-body-sm">
          {t('screen.handover.acceptance.merging', { n: report.number })}
        </p>
      )}
      {report.acceptance === 'FAILED' && (
        <p role="note" className="rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
          {t('screen.handover.acceptance.failed')}
        </p>
      )}
      {/*
        FR-86, UX-DR127: a handover the Customer of a Self-mentored Job returned says so, with the reason as written,
        to every reader. It is void too, but not because GitHub moved, so the other note is not shown for it.
      */}
      {report.returned && (
        <div role="note" className="flex flex-col gap-2 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
          {/* Nothing here speaks of the money: a later handover of the Milestone may have released it since. */}
          <p>{t('screen.handover.voided.returned', { date: formatDate(report.returned.at) })}</p>
          {/* The reason goes with the handover's other texts once the Job's data is retired (NFR-10). */}
          {report.returned.reason && (
            <figure className="flex flex-col gap-0.5">
              <figcaption className="text-label">{t('screen.handover.voided.returnReason')}</figcaption>
              <blockquote className="break-words whitespace-pre-wrap">{report.returned.reason}</blockquote>
            </figure>
          )}
        </div>
      )}
      {!report.returned && (report.status === 'VOIDED' || report.acceptance === 'VOIDED') && (
        <p role="note" className="rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
          <span>{t('reason.delivery.HANDOVER_VOIDED')}</span>
          {customer && <span> {t('screen.handover.voided.moneyHeld')}</span>}
        </p>
      )}
      {/*
        N-33 (FR-33, R28, Story 23.8b): a statement of fact above the decision, shown and never acknowledged, and
        only where the handover really holds the Mentor's confirmation. It speaks of what to do "trước khi nghiệm
        thu", so it goes once the Customer has decided (seen 2026-11-24 under "Đã nghiệm thu").
      */}
      {customer && report.decision && report.code?.teamCodeConfirmedAt && (
        <Alert tone="info">
          <p className="break-words">
            {t(report.byMember ? 'notice.teamCode.customerSelfMentored' : 'notice.teamCode.customer', {
              mentor: report.mentorName || t('screen.split.erased'),
            })}
          </p>
        </Alert>
      )}
      {report.decision && (
        <section
          aria-label={t('screen.handover.check.nextAction')}
          className="flex flex-wrap items-center justify-between gap-3 rounded-md border-l-4 border-info bg-info-soft p-4 text-body-sm"
        >
          <span className="flex min-w-60 flex-1 flex-col gap-1">
            <span>
              {report.decision.revisionAllowed
                ? t('screen.handover.check.lead', { r: report.roundsLeft })
                : t('screen.handover.check.leadNoRounds')}
            </span>
            {/* FR-86: the third decision of a Self-mentored Job's Customer, while a return is left. */}
            {report.decision.returnsLeft != null && report.decision.returnsLeft > 0 && (
              <span>{t('screen.handover.check.leadReturns', { n: report.decision.returnsLeft })}</span>
            )}
          </span>
          <Link
            to={`/cong-viec/${report.jobId}/ban-giao/${report.id}/kiem-tra`}
            className={cn(buttonVariants({ size: 'compact' }), 'no-underline')}
          >
            {t('screen.handover.check.start')}
          </Link>
        </section>
      )}
      <EvidenceSection report={report} />
      <section aria-labelledby="delivery-criteria" className="flex flex-col gap-3">
        <h3 id="delivery-criteria" className="text-h3">
          {t('screen.handover.report.criteria')}
        </h3>
        <ol className="flex flex-col gap-3">
          {report.criteria.map((criterion) => (
            <Card as="li" key={criterion.criterionId} className="flex flex-col gap-2 text-body-sm">
              <p className="font-semibold break-words">
                {t('screen.handover.report.criterion', { n: criterion.number, statement: criterion.statement })}
              </p>
              <p className="flex items-center gap-1.5">
                {criterion.met ? (
                  <CircleCheck aria-hidden="true" className="size-4 text-success" />
                ) : (
                  <CircleX aria-hidden="true" className="size-4 text-destructive" />
                )}
                {t(
                  report.byMember
                    ? criterion.met
                      ? 'screen.handover.report.memberMet'
                      : 'screen.handover.report.memberNotMet'
                    : criterion.met
                      ? 'screen.handover.report.selfMet'
                      : 'screen.handover.report.selfNotMet',
                )}
              </p>
              <p className="break-words whitespace-pre-wrap">{criterion.note}</p>
              <div className="rounded-md bg-muted p-3">
                <p className="text-label">{t('screen.handover.report.checkSteps')}</p>
                <p className="break-words whitespace-pre-wrap">{criterion.checkSteps}</p>
              </div>
              {checks.get(criterion.criterionId) && (
                <p className="flex flex-col gap-0.5 border-l-2 border-border pl-2">
                  <span className="font-semibold">
                    {t(checks.get(criterion.criterionId)?.met ? 'screen.handover.report.customerMet' : 'screen.handover.report.customerNotMet')}
                  </span>
                  {checks.get(criterion.criterionId)?.comment && (
                    <span className="break-words whitespace-pre-wrap">{checks.get(criterion.criterionId)?.comment}</span>
                  )}
                </p>
              )}
            </Card>
          ))}
        </ol>
      </section>
      {report.code && <CodeSections report={report} code={report.code} />}
    </article>
  )
}

type Code = NonNullable<DeliveryReport['code']>

/**
 * What the handover carries beside its criteria (FR-10, FR-32, AD-35, UX-DR30, Story 24.3): the preview with N-11,
 * or why a preview is impossible; every link the Mentor added, with its kind, label and domain; and the images.
 */
function EvidenceSection({ report }: { report: DeliveryReport }) {
  const { preview, noPreviewReason } = report.preview
  if (!preview && !noPreviewReason && report.links.length === 0 && report.screenshots.length === 0) {
    return null
  }
  return (
    <Card as="section" aria-labelledby="delivery-evidence" className="flex flex-col gap-3 text-body-sm">
      <h3 id="delivery-evidence" className="text-h3">
        {t('screen.handover.links.title')}
      </h3>
      {(preview ?? noPreviewReason) && (
        <div className="flex flex-col gap-1">
          <h4 className="text-label">{t('term.previewLink.label')}</h4>
          {preview && (
            <div className="flex flex-col gap-0.5">
              <OutsideLink url={preview.url} domain={preview.domain} label={t('screen.handover.preview.open')} />
              {preview.checkedAt && (
                <span className="text-caption text-muted-foreground">
                  {t('screen.handover.preview.checkedAt', { time: formatDayMonthTime(preview.checkedAt) })}
                </span>
              )}
            </div>
          )}
          {noPreviewReason && (
            <p className="flex flex-col gap-0.5">
              <span className="text-label">{t('screen.handover.preview.reasonLabel')}</span>
              <span className="break-words whitespace-pre-wrap">{noPreviewReason}</span>
            </p>
          )}
        </div>
      )}
      {report.links.length > 0 && (
        <div className="flex flex-col gap-1.5">
          <h4 className="text-label">{t('term.handoverLink.label')}</h4>
          <HandoverLinkList links={report.links} />
        </div>
      )}
      {report.screenshots.length > 0 && (
        <div className="flex flex-col gap-2">
          <h4 className="text-label">{t('screen.handover.preview.screenshots')}</h4>
          <ul className="grid grid-cols-2 gap-2 md:grid-cols-3">
            {report.screenshots.map((screenshot) => (
              <li key={screenshot.fileId}>
                <a href={`/api/v1/files/${screenshot.fileId}`} target="_blank" rel="noopener noreferrer">
                  <img
                    src={`/api/v1/files/${screenshot.fileId}`}
                    alt={screenshot.fileName}
                    className="aspect-video w-full rounded-md border border-border object-cover"
                  />
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Card>
  )
}

/**
 * A Code Job's part of the report (FR-32, AD-33, UX-DR53, UX-DR115). "Chi tiết kỹ thuật" adds the two GitHub
 * links of Story 23.8b, each with its domain: the pull request's changes and the `handover/{id}` branch in the
 * Intermediary Repository. The Customer reads them through the read invitation they accept on GitHub, so their
 * line says so and points at the invitation page (Story 23.7).
 */
function CodeSections({ report, code }: { report: DeliveryReport; code: Code }) {
  const customer = report.audience === 'CUSTOMER'
  return (
    <>
      <SensitiveChangeList changes={code.sensitiveChanges} headingId="delivery-sensitive" />
      <details className={cn('p-4 text-body-sm md:p-5', cardSurface)}>
        <summary className="cursor-pointer font-semibold">{t('screen.handover.code.technical')}</summary>
        <div className="mt-2 flex flex-col gap-2">
          <p className="text-caption text-muted-foreground">{t('screen.handover.code.technicalLead')}</p>
          <ul className="flex flex-col gap-1">
            {code.commits.map((commit) => (
              <li key={commit.sha} className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                <code className="font-mono text-caption">{commit.sha.slice(0, 7)}</code>
                <span className="min-w-0 break-words">{commit.message}</span>
              </li>
            ))}
          </ul>
          {(code.diff ?? code.branch) && (
            <div className="flex flex-col gap-1.5">
              {code.diff && (
                <OutsideLink
                  url={code.diff.url}
                  domain={code.diff.domain}
                  label={t('screen.handover.code.openDiff')}
                  warning={false}
                />
              )}
              {code.branch && (
                <OutsideLink
                  url={code.branch.url}
                  domain={code.branch.domain}
                  label={t('screen.handover.code.openBranch')}
                  warning={false}
                />
              )}
              {customer && (
                <p className="flex flex-col gap-0.5 text-caption text-muted-foreground">
                  <span>{t('screen.handover.code.linksCustomer')}</span>
                  <Link
                    to={`/cong-viec/${report.jobId}/kho-trung-gian`}
                    className="w-fit text-primary underline-offset-4 hover:underline"
                  >
                    {t('screen.handover.code.openInvitation')}
                  </Link>
                </p>
              )}
            </div>
          )}
          <p className="text-caption text-muted-foreground">{t('screen.handover.code.pull', { n: code.pullNumber })}</p>
          {code.unattributed > 0 && (
            <p className="text-caption text-muted-foreground">{t('screen.handover.code.unattributed', { n: code.unattributed })}</p>
          )}
          {code.truncated && <p className="text-caption text-muted-foreground">{t('screen.handover.code.truncated')}</p>}
        </div>
      </details>
    </>
  )
}
