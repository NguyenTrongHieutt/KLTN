import { useState } from 'react'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { entryText } from '@/features/work/historyText'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { cn } from '@/lib/utils'
import type { AdminDispute } from './adminDisputesApi'
import {
  useAgreementEvidence,
  useBoardEvidence,
  useCriteriaEvidence,
  useDecisionsEvidence,
  useDriftEvidence,
  useEvidenceAudit,
  useJobChatEvidence,
  usePageVersionEvidence,
  usePageVersionsEvidence,
  usePagesEvidence,
  usePaymentsEvidence,
  useWorkItemEvidence,
} from './disputeEvidenceApi'

const TABS = ['agreement', 'criteria', 'work', 'pages', 'chat', 'decisions', 'payments', 'drift', 'audit'] as const

type Tab = (typeof TABS)[number]

/**
 * ADM-4's Evidence Package (FR-53, FR-63, AD-20, AD-31, UX-DR58): one tab per FR-53 source, read only when
 * it opens. The Agreement versions, the criteria in each Delivery Report, the approvals and revision
 * requests, the payments, the Drift Warnings, and the Audit Log come from `dispute`; the Job Chat, the Work
 * Items, and the Pages from `chat` and `work` under the assignee's grant. Every view is audited, and the
 * note says so. Never the Crew Chat, the Crew Terms, or private feedback.
 */
export function DisputeEvidence({ data }: { data: AdminDispute }) {
  const [tab, setTab] = useState<Tab>('agreement')
  return (
    <section aria-labelledby="dispute-evidence" className="flex flex-col gap-3">
      <h3 id="dispute-evidence" className="text-h3">
        {t('screen.admin.disputes.evidence.title')}
      </h3>
      <p className="text-caption text-muted-foreground">{t('screen.admin.disputes.evidence.note')}</p>
      <div role="tablist" aria-label={t('screen.admin.disputes.evidence.title')} className="flex flex-wrap gap-2">
        {TABS.map((option) => (
          <button
            key={option}
            type="button"
            role="tab"
            aria-selected={tab === option}
            onClick={() => setTab(option)}
            className={cn(
              'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              tab === option ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
            )}
          >
            {t(`screen.admin.disputes.evidence.tab.${option}` as MessageKey)}
          </button>
        ))}
      </div>
      <div role="tabpanel" className="flex flex-col gap-2">
        {tab === 'agreement' && <AgreementTab disputeId={data.id} />}
        {tab === 'criteria' && <CriteriaTab disputeId={data.id} />}
        {tab === 'work' && <WorkTab jobId={data.jobId} />}
        {tab === 'pages' && <PagesTab jobId={data.jobId} />}
        {tab === 'chat' && <ChatTab jobId={data.jobId} />}
        {tab === 'decisions' && <DecisionsTab disputeId={data.id} />}
        {tab === 'payments' && <PaymentsTab disputeId={data.id} />}
        {tab === 'drift' && <DriftTab disputeId={data.id} />}
        {tab === 'audit' && <AuditTab disputeId={data.id} />}
      </div>
    </section>
  )
}

function Empty() {
  return <p className="text-muted-foreground">{t('screen.admin.disputes.evidence.empty')}</p>
}

function AgreementTab({ disputeId }: { disputeId: string }) {
  const versions = useAgreementEvidence(disputeId)
  return (
    <RegionState query={versions} skeleton={<Skeleton className="h-32 w-full" />}>
      {versions.data && versions.data.length === 0 && <Empty />}
      <ul className="flex flex-col gap-2">
        {versions.data?.map((version) => (
          <li key={version.versionNo} className="flex flex-col gap-1 rounded-md bg-muted p-3">
            <p className="font-semibold">
              {t('screen.admin.disputes.evidence.version', {
                n: version.versionNo,
                time: formatDayMonthTime(version.confirmedAt),
              })}
            </p>
            <p className="break-words whitespace-pre-line">{version.scope}</p>
            {version.milestones.map((milestone) => (
              <div key={milestone.milestoneId} className="flex flex-col gap-0.5">
                <p className="text-label">
                  {t('screen.admin.disputes.evidence.milestone', { n: milestone.number, title: milestone.title })}
                  {milestone.priceVnd != null && (
                    <>
                      {' · '}
                      {formatVnd(milestone.priceVnd)} <TestTag />
                    </>
                  )}
                </p>
                <ol className="list-decimal pl-5 text-caption">
                  {milestone.criteria.map((criterion) => (
                    <li key={criterion.criterionId} className="break-words">
                      {criterion.statement}
                    </li>
                  ))}
                </ol>
              </div>
            ))}
          </li>
        ))}
      </ul>
    </RegionState>
  )
}

function mark(met: boolean | null | undefined) {
  if (met == null) {
    return t('screen.admin.disputes.evidence.unchecked')
  }
  return t(met ? 'screen.admin.disputes.evidence.met' : 'screen.admin.disputes.evidence.notMet')
}

function CriteriaTab({ disputeId }: { disputeId: string }) {
  const reports = useCriteriaEvidence(disputeId)
  return (
    <RegionState query={reports} skeleton={<Skeleton className="h-32 w-full" />}>
      {reports.data && reports.data.length === 0 && <Empty />}
      <ul className="flex flex-col gap-2">
        {reports.data?.map((report) => (
          <li key={report.handoverId} className="flex flex-col gap-1 rounded-md bg-muted p-3">
            <p className="font-semibold">
              {t('screen.admin.disputes.evidence.report', {
                n: report.milestoneNumber,
                title: report.milestoneTitle,
                round: report.round,
              })}
            </p>
            <p className="text-caption text-muted-foreground">
              {t(`screen.admin.disputes.evidence.reportStatus.${report.status}` as MessageKey)} ·{' '}
              {formatDayMonthTime(report.submittedAt)}
            </p>
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
          </li>
        ))}
      </ul>
    </RegionState>
  )
}

function WorkTab({ jobId }: { jobId: string }) {
  const board = useBoardEvidence(jobId)
  const [open, setOpen] = useState<string>()
  return (
    <RegionState query={board} skeleton={<Skeleton className="h-32 w-full" />}>
      {board.data && board.data.items.length === 0 && <Empty />}
      <ul className="flex flex-col gap-2">
        {board.data?.items.map((item) => (
          <li key={item.id} className="flex flex-col gap-1 rounded-md bg-muted p-3">
            <button
              type="button"
              aria-expanded={open === item.id}
              onClick={() => setOpen(open === item.id ? undefined : item.id)}
              className="text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
            >
              <span className="font-semibold">{item.key}</span> <span className="break-words">{item.title}</span>
              {item.assignee && <span className="text-caption text-muted-foreground"> · {item.assignee.name}</span>}
            </button>
            {open === item.id && <WorkItemHistory workItemId={item.id} />}
          </li>
        ))}
      </ul>
    </RegionState>
  )
}

function WorkItemHistory({ workItemId }: { workItemId: string }) {
  const detail = useWorkItemEvidence(workItemId)
  return (
    <RegionState query={detail} skeleton={<Skeleton className="h-16 w-full" />}>
      {detail.data && (
        <div className="flex flex-col gap-1 text-caption">
          {detail.data.attachments.length > 0 && (
            <p className="break-words">
              {t('screen.admin.disputes.evidence.attachments', {
                names: detail.data.attachments.map((attachment) => attachment.name).join(', '),
              })}
            </p>
          )}
          <ol className="flex flex-col gap-1">
            {detail.data.history.map((entry) => (
              <li key={entry.id} className="break-words">
                <span className="text-muted-foreground">{formatDayMonthTime(entry.at)} · </span>
                {entryText(entry)}
                {entry.comment ? `: ${entry.comment}` : ''}
              </li>
            ))}
          </ol>
        </div>
      )}
    </RegionState>
  )
}

function PagesTab({ jobId }: { jobId: string }) {
  const tree = usePagesEvidence(jobId)
  const [open, setOpen] = useState<string>()
  return (
    <RegionState query={tree} skeleton={<Skeleton className="h-32 w-full" />}>
      {tree.data && tree.data.pages.length === 0 && <Empty />}
      <ul className="flex flex-col gap-2">
        {tree.data?.pages.map((page) => (
          <li key={page.id} className="flex flex-col gap-1 rounded-md bg-muted p-3">
            <button
              type="button"
              aria-expanded={open === page.id}
              onClick={() => setOpen(open === page.id ? undefined : page.id)}
              className="text-left font-semibold break-words focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
            >
              {page.title}
            </button>
            {open === page.id && <PageVersions pageId={page.id} />}
          </li>
        ))}
      </ul>
    </RegionState>
  )
}

function PageVersions({ pageId }: { pageId: string }) {
  const versions = usePageVersionsEvidence(pageId)
  const [shown, setShown] = useState<number>()
  const version = usePageVersionEvidence(pageId, shown)
  return (
    <RegionState query={versions} skeleton={<Skeleton className="h-16 w-full" />}>
      <div className="flex flex-wrap gap-2">
        {versions.data?.map((entry) => (
          <Button
            key={entry.versionNo}
            type="button"
            variant={shown === entry.versionNo ? 'primary' : 'secondary'}
            size="compact"
            onClick={() => setShown(entry.versionNo)}
          >
            {t('screen.admin.disputes.evidence.pageVersion', { n: entry.versionNo, time: formatDayMonthTime(entry.at) })}
          </Button>
        ))}
      </div>
      {version.data && (
        <div className="rounded-md bg-background p-3">
          <p className="text-caption text-muted-foreground">{version.data.author}</p>
          <p className="break-words whitespace-pre-line">{version.data.body}</p>
        </div>
      )}
    </RegionState>
  )
}

function ChatTab({ jobId }: { jobId: string }) {
  const chat = useJobChatEvidence(jobId)
  return (
    <RegionState query={chat} skeleton={<Skeleton className="h-32 w-full" />}>
      {chat.data && chat.data.items.length === 0 && <Empty />}
      <ul className="flex flex-col gap-2">
        {chat.data?.items.map((message) => (
          <li key={message.id} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
            <p className="text-caption text-muted-foreground">
              {message.author} · {formatDayMonthTime(message.sentAt)}
            </p>
            <p className="break-words whitespace-pre-line">{message.body}</p>
          </li>
        ))}
      </ul>
    </RegionState>
  )
}

function DecisionsTab({ disputeId }: { disputeId: string }) {
  const decisions = useDecisionsEvidence(disputeId)
  return (
    <RegionState query={decisions} skeleton={<Skeleton className="h-32 w-full" />}>
      {decisions.data && decisions.data.length === 0 && <Empty />}
      <ol className="flex flex-col gap-1">
        {decisions.data?.map((line, index) => (
          <li key={`${line.kind}-${index}`} className="rounded-md bg-muted p-3 break-words">
            <span className="text-caption text-muted-foreground">{formatDayMonthTime(line.at)} · </span>
            {t(`screen.admin.disputes.evidence.decision.${line.kind}` as MessageKey, {
              n: line.versionNo ?? line.milestoneNumber ?? 0,
              title: line.milestoneTitle ?? '',
              round: line.round ?? 0,
            })}
          </li>
        ))}
      </ol>
    </RegionState>
  )
}

function PaymentsTab({ disputeId }: { disputeId: string }) {
  const payments = usePaymentsEvidence(disputeId)
  return (
    <RegionState query={payments} skeleton={<Skeleton className="h-32 w-full" />}>
      {payments.data && payments.data.length === 0 && <Empty />}
      <ul className="flex flex-col gap-2">
        {payments.data?.map((milestone) => (
          <li key={milestone.milestoneId} className="flex flex-col gap-1 rounded-md bg-muted p-3">
            <p className="font-semibold">
              {t('screen.admin.disputes.evidence.milestone', { n: milestone.number, title: milestone.title })}
            </p>
            {milestone.moneyIn.map((line, index) => (
              <p key={`in-${index}`} className="text-caption">
                {formatDayMonthTime(line.at)} ·{' '}
                {t(
                  `screen.admin.disputes.evidence.moneyIn.${line.type === 'FUND' && line.bucket === 'CHANGE_REQUEST' ? 'TOP_UP_FUND' : line.type}` as MessageKey,
                )}{' '}
                {formatVnd(line.amountVnd)} <TestTag />
              </p>
            ))}
            {milestone.refunds.map((refund, index) => (
              <p key={`refund-${index}`} className="text-caption">
                {formatDayMonthTime(refund.requestedAt)} ·{' '}
                {t('screen.admin.disputes.evidence.refund', {
                  reason: t(`screen.admin.disputes.evidence.refundReason.${refund.reason}` as MessageKey),
                  status: t(`screen.admin.disputes.evidence.refundStatus.${refund.status}` as MessageKey),
                })}{' '}
                {formatVnd(refund.amountVnd)} <TestTag />
              </p>
            ))}
            <p className="text-label">
              {t('screen.admin.disputes.evidence.totals', {
                released: formatVnd(milestone.releasedTotalVnd),
                releasable: formatVnd(milestone.releasableVnd),
              })}{' '}
              <TestTag />
            </p>
          </li>
        ))}
      </ul>
    </RegionState>
  )
}

function DriftTab({ disputeId }: { disputeId: string }) {
  const drift = useDriftEvidence(disputeId)
  return (
    <RegionState query={drift} skeleton={<Skeleton className="h-32 w-full" />}>
      {drift.data && drift.data.length === 0 && <Empty />}
      <ul className="flex flex-col gap-2">
        {drift.data?.map((warning) => (
          <li key={warning.id} className="flex flex-col gap-1 rounded-md bg-muted p-3">
            <p className="font-semibold">
              {t(`screen.admin.disputes.evidence.drift.${warning.kind}` as MessageKey, { name: warning.fullName ?? '' })}
            </p>
            <p className="text-caption text-muted-foreground">{formatDayMonthTime(warning.detectedAt)}</p>
            {warning.commits.length > 0 && (
              <ul className="font-mono text-caption">
                {warning.commits.map((sha) => (
                  <li key={sha} className="break-all">
                    {sha.slice(0, 12)}
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </RegionState>
  )
}

function AuditTab({ disputeId }: { disputeId: string }) {
  const [page, setPage] = useState(0)
  const audit = useEvidenceAudit(disputeId, page)
  const pages = audit.data ? Math.max(1, Math.ceil(audit.data.total / audit.data.size)) : 1
  return (
    <RegionState query={audit} skeleton={<Skeleton className="h-32 w-full" />}>
      {audit.data && audit.data.items.length === 0 && <Empty />}
      <ol className="flex flex-col gap-1">
        {audit.data?.items.map((entry) => (
          <li key={entry.id} className="rounded-md bg-muted px-3 py-2 text-caption break-words">
            <span className="text-muted-foreground">
              {formatDayMonthTime(entry.occurredAt)} ·{' '}
              {entry.system ? t('screen.admin.disputes.evidence.system') : (entry.actorName ?? '')} ·{' '}
            </span>
            <span className="font-mono">{entry.action}</span>
          </li>
        ))}
      </ol>
      {pages > 1 && (
        <div className="flex items-center gap-2">
          <Button type="button" variant="secondary" size="compact" disabled={page === 0} onClick={() => setPage(page - 1)}>
            {t('screen.admin.disputes.evidence.newer')}
          </Button>
          <span className="text-caption">{t('screen.admin.disputes.evidence.page', { n: page + 1, total: pages })}</span>
          <Button type="button" variant="secondary" size="compact" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
            {t('screen.admin.disputes.evidence.older')}
          </Button>
        </div>
      )}
    </RegionState>
  )
}
