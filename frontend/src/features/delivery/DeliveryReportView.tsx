import { CircleCheck, CircleX } from 'lucide-react'
import { Link } from 'react-router'
import { SafeMarkdown } from '@/components/SafeMarkdown'
import { buttonVariants } from '@/components/ui/button-variants'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate, formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { cn } from '@/lib/utils'
import type { DeliveryReport } from './deliveryApi'
import { OutsideLink } from './OutsideLink'
import { SensitiveChangeList } from './SensitiveChangeList'

/**
 * The Delivery Report (FR-10, FR-32, AD-33, UX-DR53): per criterion the Mentor's own statement
 * "Người hướng dẫn tự nhận xét: Đạt / Chưa đạt", never counted as a criterion done, their note, and
 * "Cách kiểm tra"; the notes on Work Items that were not done for the Mentor and the Crew only; an
 * Assessment Job's Report at the version handed over, in the Markdown view. A Code Job's report adds the
 * Sensitive change list, the Pages updated during the Milestone, and the collapsed "Chi tiết kỹ thuật"
 * with the commits, all rendered as text (NFR-3). While it waits for the Customer, they get "Kiểm tra
 * từng tiêu chí" and the Revision Rounds left; once decided, everyone sees the Customer's marks.
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
          {t('screen.handover.report.meta', { mentor: report.mentorName, date: formatDate(report.createdAt), round: report.round })}
        </p>
        <p className="w-fit rounded-full bg-primary-soft px-2 py-0.5 text-caption font-semibold text-primary">
          {report.auto
            ? t('screen.handover.status.auto')
            : customer && report.status === 'SUBMITTED'
              ? t('screen.handover.status.customer', { date: formatDayMonth(report.checkBy) })
              : t(`screen.handover.status.${report.status}` as MessageKey)}
        </p>
      </header>
      {report.decision && (
        <section
          aria-label={t('screen.handover.check.nextAction')}
          className="flex flex-wrap items-center justify-between gap-3 rounded-lg border-l-4 border-primary bg-primary-soft p-4 text-body-sm"
        >
          <span>
            {report.decision.revisionAllowed
              ? t('screen.handover.check.lead', { r: report.roundsLeft })
              : t('screen.handover.check.leadNoRounds')}
          </span>
          <Link
            to={`/cong-viec/${report.jobId}/ban-giao/${report.id}/kiem-tra`}
            className={cn(buttonVariants({ size: 'compact' }), 'no-underline')}
          >
            {t('screen.handover.check.start')}
          </Link>
        </section>
      )}
      <PreviewSection report={report} />
      <section aria-labelledby="delivery-criteria" className="flex flex-col gap-3">
        <h3 id="delivery-criteria" className="text-h3">
          {t('screen.handover.report.criteria')}
        </h3>
        <ol className="flex flex-col gap-3">
          {report.criteria.map((criterion) => (
            <li key={criterion.criterionId} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4 text-body-sm">
              <p className="font-semibold break-words">
                {t('screen.handover.report.criterion', { n: criterion.number, statement: criterion.statement })}
              </p>
              <p className="flex items-center gap-1.5">
                {criterion.met ? (
                  <CircleCheck aria-hidden="true" className="size-4 text-success" />
                ) : (
                  <CircleX aria-hidden="true" className="size-4 text-destructive" />
                )}
                {t(criterion.met ? 'screen.handover.report.selfMet' : 'screen.handover.report.selfNotMet')}
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
            </li>
          ))}
        </ol>
      </section>
      {!customer && report.itemNotes.length > 0 && (
        <section aria-labelledby="delivery-items" className="flex flex-col gap-2">
          <h3 id="delivery-items" className="text-h3">
            {t('screen.handover.report.items')}
          </h3>
          <ul className="flex flex-col gap-2 text-body-sm">
            {report.itemNotes.map((note) => (
              <li key={note.workItemId} className="flex flex-col gap-0.5 rounded-md bg-muted px-3 py-2">
                <span>
                  <code className="font-mono text-caption">{note.key}</code> <span className="break-words">{note.title}</span>
                </span>
                <span className="break-words whitespace-pre-wrap">{note.note}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {report.code && <CodeSections report={report} code={report.code} />}
      {report.report && (
        <section aria-labelledby="delivery-assessment" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
          <h3 id="delivery-assessment" className="text-h3 break-words">
            {t('screen.handover.report.assessment', { title: report.report.title, version: report.report.versionNo })}
          </h3>
          <SafeMarkdown>{report.report.body}</SafeMarkdown>
        </section>
      )}
    </article>
  )
}

type Code = NonNullable<DeliveryReport['code']>

/** The preview or the recording with N-11, and the screenshots (FR-32, AD-35, UX-DR30). */
function PreviewSection({ report }: { report: DeliveryReport }) {
  const { preview, recording, noPreviewReason } = report.preview
  if (!preview && !recording && report.screenshots.length === 0) {
    return null
  }
  return (
    <section aria-labelledby="delivery-preview" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4 text-body-sm">
      <h3 id="delivery-preview" className="text-h3">
        {t('term.previewLink.label')}
      </h3>
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
      {recording && (
        <div className="flex flex-col gap-1">
          <OutsideLink url={recording.url} domain={recording.domain} label={t('screen.handover.preview.openRecording')} />
          {noPreviewReason && (
            <p className="flex flex-col gap-0.5">
              <span className="text-label">{t('screen.handover.preview.reasonLabel')}</span>
              <span className="break-words whitespace-pre-wrap">{noPreviewReason}</span>
            </p>
          )}
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
    </section>
  )
}

/** A Code Job's part of the report (FR-32, AD-33, UX-DR53). */
function CodeSections({ report, code }: { report: DeliveryReport; code: Code }) {
  return (
    <>
      <SensitiveChangeList changes={code.sensitiveChanges} headingId="delivery-sensitive" />
      <section aria-labelledby="delivery-pages" className="flex flex-col gap-2">
        <h3 id="delivery-pages" className="text-h3">
          {t('screen.handover.code.pagesTitle')}
        </h3>
        {code.pages.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.handover.code.pagesNone')}</p>
        ) : (
          <ul className="flex flex-col gap-1 text-body-sm">
            {code.pages.map((page) => (
              <li key={page.pageId}>
                <Link
                  to={`/cong-viec/${report.jobId}/tai-lieu/${page.pageId}`}
                  className="break-words text-primary underline-offset-4 hover:underline"
                >
                  {t('screen.handover.code.pageVersion', { title: page.title, version: page.versionNo })}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      <details className="rounded-lg border border-border bg-card p-4 text-body-sm">
        <summary className="cursor-pointer font-semibold">{t('screen.handover.code.technical')}</summary>
        <div className="mt-2 flex flex-col gap-2">
          <p className="text-caption text-muted-foreground">{t('screen.handover.code.technicalLead')}</p>
          <ul className="flex flex-col gap-1">
            {code.commits.map((commit) => (
              <li key={commit.sha} className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                <code className="font-mono text-caption">{commit.sha.slice(0, 7)}</code>
                <span className="min-w-0 break-words">{commit.message}</span>
                {commit.workItemKeys.map((key) => (
                  <code key={key} className="rounded bg-muted px-1 font-mono text-caption">
                    {key}
                  </code>
                ))}
              </li>
            ))}
          </ul>
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
