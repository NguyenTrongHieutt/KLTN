import { HandoverLinkList } from '@/features/delivery/HandoverLinkList'
import { OutsideLink } from '@/features/delivery/OutsideLink'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import type { EvidenceReport } from './disputeEvidenceApi'

function mark(met: boolean | null | undefined) {
  if (met == null) {
    return t('screen.admin.disputes.evidence.unchecked')
  }
  return t(met ? 'screen.admin.disputes.evidence.met' : 'screen.admin.disputes.evidence.notMet')
}

/**
 * One handover as both Evidence Packages show it (FR-53, FR-84, AD-31, AD-40, Story 24.3b): the Dispute's "criteria"
 * tab and the Split Resolution's "Bàn giao" tab read the same view from the server and draw it here alike. Its caption
 * says when it was sent and, once decided, when; a round the System accepted when the check period ran out says so, as
 * its criteria then carry no mark of the Customer's. Its criteria come first, the Mentor's mark and note beside the Customer's; then what the handover carried, as the
 * Customer's Delivery Report shows it: the preview with its domain and N-11, or the Mentor's reason there is none; every
 * link with its kind, label and domain, opening in a new tab without the opener (AD-35); and the images as thumbnails
 * opening `/api/v1/files/{fileId}`, each read audited by the server. Nothing a link names is ever fetched (AD-32).
 */
export function EvidenceReportItem({ report }: { report: EvidenceReport }) {
  return (
    <li className="flex flex-col gap-2 rounded-md bg-muted p-3">
      <div className="flex flex-col gap-0.5">
        <p className="font-semibold">
          {t('screen.admin.disputes.evidence.report', {
            n: report.milestoneNumber,
            title: report.milestoneTitle,
            round: report.round,
          })}
        </p>
        <p className="text-caption text-muted-foreground">
          {[
            report.auto
              ? t('screen.admin.disputes.evidence.autoAccepted')
              : t(`screen.admin.disputes.evidence.reportStatus.${report.status}` as MessageKey),
            formatDayMonthTime(report.submittedAt),
            report.decidedAt
              ? t('screen.admin.disputes.evidence.decidedAt', { time: formatDayMonthTime(report.decidedAt) })
              : '',
          ]
            .filter((part) => part !== '')
            .join(' · ')}
        </p>
      </div>
      <table className="w-full text-left text-caption">
        <thead>
          <tr>
            <th className="py-1 pr-2 font-semibold">{t('screen.admin.disputes.evidence.criterion')}</th>
            <th className="py-1 pr-2 font-semibold">{t('screen.admin.disputes.evidence.reported')}</th>
            <th className="py-1 font-semibold">{t('screen.admin.disputes.evidence.checked')}</th>
          </tr>
        </thead>
        <tbody>
          {report.criteria.map((criterion) => (
            <tr key={criterion.criterionId} className="border-t border-border align-top">
              <td className="py-1 pr-2 break-words">
                {criterion.number}. {criterion.statement}
              </td>
              <td className="py-1 pr-2 break-words">
                {mark(criterion.reportedMet)}
                {criterion.reportNote ? ` · ${criterion.reportNote}` : ''}
              </td>
              <td className="py-1 break-words">
                {mark(criterion.customerMet)}
                {criterion.customerComment ? ` · ${criterion.customerComment}` : ''}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <ReportEvidence report={report} />
    </li>
  )
}

/** What the handover carried beside its criteria; nothing when it carried nothing. */
function ReportEvidence({ report }: { report: EvidenceReport }) {
  const { preview, noPreviewReason, links, images } = report
  if (!preview && !noPreviewReason && links.length === 0 && images.length === 0) {
    return null
  }
  return (
    <div className="flex flex-col gap-2 border-t border-border pt-2 text-body-sm">
      <p className="text-label">{t('screen.admin.disputes.evidence.carried')}</p>
      {preview && (
        <div className="flex flex-col gap-0.5">
          <span className="text-caption font-semibold text-muted-foreground">
            {t('screen.admin.disputes.evidence.preview')}
          </span>
          <OutsideLink url={preview.url} domain={preview.domain} label={t('screen.admin.disputes.evidence.openPreview')} />
        </div>
      )}
      {noPreviewReason && (
        <p className="flex flex-col gap-0.5">
          <span className="text-caption font-semibold text-muted-foreground">
            {t('screen.admin.disputes.evidence.noPreviewReason')}
          </span>
          <span className="break-words whitespace-pre-wrap">{noPreviewReason}</span>
        </p>
      )}
      {links.length > 0 && (
        <div className="flex flex-col gap-1">
          <span className="text-caption font-semibold text-muted-foreground">
            {t('screen.admin.disputes.evidence.links')}
          </span>
          <HandoverLinkList links={links} />
        </div>
      )}
      {images.length > 0 && (
        <div className="flex flex-col gap-1">
          <span className="text-caption font-semibold text-muted-foreground">
            {t('screen.admin.disputes.evidence.images')}
          </span>
          <ul className="grid grid-cols-2 gap-2 md:grid-cols-3">
            {images.map((image) => (
              <li key={image.fileId}>
                <a href={`/api/v1/files/${image.fileId}`} target="_blank" rel="noopener noreferrer">
                  <img
                    src={`/api/v1/files/${image.fileId}`}
                    alt={image.fileName}
                    className="aspect-video w-full rounded-md border border-border object-cover"
                  />
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
