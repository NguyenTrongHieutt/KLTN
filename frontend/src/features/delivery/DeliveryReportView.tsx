import { CircleCheck, CircleX } from 'lucide-react'
import { SafeMarkdown } from '@/components/SafeMarkdown'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate, formatDayMonth } from '@/lib/dates'
import type { DeliveryReport } from './deliveryApi'

/**
 * The Delivery Report (FR-10, FR-32, AD-33, UX-DR53): per criterion the Mentor's own statement
 * "Người hướng dẫn tự nhận xét: Đạt / Chưa đạt", never counted as a criterion done, their note, and
 * "Cách kiểm tra"; the notes on Work Items that were not done for the Mentor and the Crew only; an
 * Assessment Job's Report at the version handed over, in the Markdown view.
 */
export function DeliveryReportView({ report }: { report: DeliveryReport }) {
  const customer = report.audience === 'CUSTOMER'
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
          {customer
            ? t('screen.handover.status.customer', { date: formatDayMonth(report.checkBy) })
            : t(`screen.handover.status.${report.status}` as MessageKey)}
        </p>
      </header>
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
