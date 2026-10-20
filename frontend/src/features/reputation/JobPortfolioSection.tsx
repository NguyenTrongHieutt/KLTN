import { useId, useState } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { usePortfolio, useSummaryCommand, type JobPortfolio, type OwnEntry } from './portfolioApi'

/**
 * JOB-1's portfolio section from Closed (FR-50, UX-DR55): the Mentor drafts the summary, the Customer
 * approves it (possibly edited, with or without the Product name) or declines it before the Rating
 * Window ends, and the Mentor and each Crew member see their own entry, built from records, and whether
 * it is published yet (after their data-deletion confirmation, FR-66).
 */
export function JobPortfolioSection({ jobId }: { jobId: string }) {
  const portfolio = usePortfolio(jobId)
  // An auxiliary part of the overview: nothing while loading, when the read fails, or without entries.
  return portfolio.data?.exists ? <Content jobId={jobId} portfolio={portfolio.data} /> : null
}

function Content({ jobId, portfolio }: { jobId: string; portfolio: JobPortfolio }) {
  const ids = useId()
  const date = portfolio.decideBy ? formatDayMonth(portfolio.decideBy) : ''
  const status = portfolio.summaryStatus
  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id={`${ids}-title`} className="text-h2">
        {t('screen.portfolioSection.heading')}
      </h2>
      <p className="text-body-sm text-muted-foreground">{t('screen.portfolioSection.intro')}</p>
      {portfolio.audience === 'MENTOR' && status === 'DRAFT_DUE' && portfolio.open && <DraftForm jobId={jobId} date={date} />}
      {portfolio.audience === 'CUSTOMER' && status === 'AWAITING_APPROVAL' && portfolio.open && (
        <ApprovalForm jobId={jobId} portfolio={portfolio} date={date} />
      )}
      <SummaryStatus portfolio={portfolio} date={date} />
      {portfolio.mine && <OwnEntryCard entry={portfolio.mine} />}
    </section>
  )
}

function SummaryStatus({ portfolio, date }: { portfolio: JobPortfolio; date: string }) {
  const status = portfolio.summaryStatus
  const lines: string[] = []
  if (status === 'DRAFT_DUE' && portfolio.audience !== 'MENTOR') {
    lines.push(t('screen.portfolioSection.waitingDraft'))
  }
  if (status === 'AWAITING_APPROVAL' && portfolio.audience !== 'CUSTOMER') {
    lines.push(t('screen.portfolioSection.waitingApproval', { date }))
  }
  if (status === 'APPROVED') {
    lines.push(t('screen.portfolioSection.statusApproved'))
  }
  if (status === 'DECLINED') {
    lines.push(t('screen.portfolioSection.statusDeclined'))
  }
  if (status === 'LAPSED') {
    lines.push(t('screen.portfolioSection.statusLapsed'))
  }
  const showText = portfolio.summaryText && !(portfolio.audience === 'CUSTOMER' && status === 'AWAITING_APPROVAL')
  if (lines.length === 0 && !showText) {
    return null
  }
  return (
    <div className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
      {lines.map((line) => (
        <p key={line}>{line}</p>
      ))}
      {showText && (
        <p className="break-words whitespace-pre-line">
          <span className="text-label">{t('screen.portfolioSection.summary')}: </span>
          {portfolio.summaryText}
        </p>
      )}
    </div>
  )
}

function DraftForm({ jobId, date }: { jobId: string; date: string }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useSummaryCommand(jobId)
  const [text, setText] = useState('')
  const [error, setError] = useState<string>()
  const send = () => {
    setError(undefined)
    command.mutate(
      { action: 'draft', text: text.trim() },
      {
        onSuccess: () => toasts.success(t('screen.portfolioSection.draftSent')),
        onError: (failure) => setError(problemMessage(failure, { module: 'reputation' }).text),
      },
    )
  }
  return (
    <div className="flex flex-col gap-2 rounded-md border border-border bg-background p-3">
      <p className="text-body-sm">{t('screen.portfolioSection.draftLead', { date })}</p>
      <label htmlFor={`${ids}-text`} className="text-label">
        {t('screen.portfolioSection.draftLabel')}
      </label>
      <textarea
        id={`${ids}-text`}
        value={text}
        maxLength={600}
        onChange={(event) => setText(event.target.value)}
        className={cn(inputClass, 'min-h-18')}
      />
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button type="button" disabled={text.trim() === '' || command.isPending} onClick={send}>
          {t('screen.portfolioSection.draftSubmit')}
        </Button>
      </div>
    </div>
  )
}

function ApprovalForm({ jobId, portfolio, date }: { jobId: string; portfolio: JobPortfolio; date: string }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useSummaryCommand(jobId)
  const [text, setText] = useState(portfolio.summaryText ?? '')
  const [showName, setShowName] = useState(false)
  const [error, setError] = useState<string>()
  const run = (action: 'approve' | 'decline') => {
    setError(undefined)
    command.mutate(action === 'approve' ? { action, text: text.trim(), showProductName: showName } : { action }, {
      onSuccess: () => toasts.success(t(action === 'approve' ? 'screen.portfolioSection.approved' : 'screen.portfolioSection.declined')),
      onError: (failure) => setError(problemMessage(failure, { module: 'reputation' }).text),
    })
  }
  return (
    <div className="flex flex-col gap-2 rounded-md border border-border bg-background p-3">
      <p className="text-body-sm">{t('screen.portfolioSection.approveLead', { date })}</p>
      <label htmlFor={`${ids}-text`} className="text-label">
        {t('screen.portfolioSection.approveLabel')}
      </label>
      <textarea
        id={`${ids}-text`}
        value={text}
        maxLength={600}
        onChange={(event) => setText(event.target.value)}
        className={cn(inputClass, 'min-h-18')}
      />
      {portfolio.productName && (
        <label htmlFor={`${ids}-name`} className="flex min-h-tap-min items-center gap-2 text-body-sm">
          <input
            id={`${ids}-name`}
            type="checkbox"
            className="size-4"
            checked={showName}
            onChange={(event) => setShowName(event.target.checked)}
          />
          {t('screen.portfolioSection.showProductName', { name: portfolio.productName })}
        </label>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-3">
        <Button type="button" variant="secondary" disabled={command.isPending} onClick={() => run('decline')}>
          {t('screen.portfolioSection.decline')}
        </Button>
        <Button type="button" disabled={text.trim() === '' || command.isPending} onClick={() => run('approve')}>
          {t('screen.portfolioSection.approve')}
        </Button>
      </div>
    </div>
  )
}

function OwnEntryCard({ entry }: { entry: OwnEntry }) {
  const facts =
    entry.role === 'MENTOR'
      ? [
          t('screen.portfolioSection.milestonesHandedOver', { n: entry.milestonesHandedOver ?? 0 }),
          t('screen.portfolioSection.reviewsWritten', { n: entry.reviewsWritten ?? 0 }),
        ]
      : [t('screen.portfolioSection.workItemsApproved', { n: entry.workItemsApproved ?? 0 })]
  return (
    <div className="flex flex-col gap-1 rounded-md border border-border bg-background p-3 text-body-sm">
      <h3 className="text-h3">{t('screen.portfolioSection.mine')}</h3>
      <p className="break-words">
        {[
          entry.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(' · '),
          t('screen.portfolioSection.milestones', { n: entry.milestones }),
          ...facts,
        ].join(' · ')}
      </p>
      {entry.skills.length > 0 && (
        <p className="text-caption text-muted-foreground">
          {t('screen.portfolioSection.skills', { skills: entry.skills.join(', ') })}
        </p>
      )}
      <p className={cn('text-caption', entry.publishedAt ? 'text-success' : 'text-muted-foreground')}>
        {t(entry.publishedAt ? 'screen.portfolioSection.published' : 'screen.portfolioSection.unpublished')}
      </p>
    </div>
  )
}
