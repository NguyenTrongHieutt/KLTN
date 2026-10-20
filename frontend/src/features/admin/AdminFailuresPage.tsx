import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { components } from '@/api/schema'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { OVERVIEW_QUERY_KEY } from './adminApi'
import { StepUpDialog } from './StepUpDialog'

type Failures = components['schemas']['FailuresView']

const FAILURES_QUERY_KEY = ['admin', 'failures'] as const

/**
 * ADM-7 (`/quan-tri/thao-tac-loi`, FR-64, AD-6, AD-9, SG-10): what failed for good, in one place. A failed
 * operation is retried behind the step-up from its first attempt, reading back first so no effect repeats;
 * a failed Dispute execution is retried on ADM-4; stalled event publications are listed; a false positive
 * can be reopened, which blocks again what an open Finding blocks.
 */
export function AdminFailuresPage() {
  usePageTitle(t('screen.admin.failures.title'))
  const failures = useQuery({
    queryKey: FAILURES_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/failures')),
    retry: false,
  })
  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.failures.heading')}</PageHeading>
      <RegionState query={failures} skeleton={<Skeleton className="h-96 w-full" />}>
        {failures.data && <Queues failures={failures.data} />}
      </RegionState>
    </div>
  )
}

function Queues({ failures }: { failures: Failures }) {
  const toasts = useToasts()
  const queryClient = useQueryClient()
  const [retrying, setRetrying] = useState<string>()
  const [error, setError] = useState<string>()
  const settle = (saved: Failures) => {
    queryClient.setQueryData(FAILURES_QUERY_KEY, saved)
    void queryClient.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY })
  }
  const retry = useMutation({
    mutationFn: ({ operationId, code }: { operationId: string; code: string }) =>
      unwrap(
        apiClient.POST('/api/v1/admin/failures/operations/{operationId}/retry', {
          params: { path: { operationId } },
          body: { stepUpCode: code },
        }),
      ),
    onSuccess: (saved) => {
      settle(saved)
      setRetrying(undefined)
      toasts.success(t('screen.admin.failures.retried'))
    },
  })
  const reopen = useMutation({
    mutationFn: (findingId: string) =>
      unwrap(apiClient.POST('/api/v1/admin/failures/findings/{findingId}/reopen', { params: { path: { findingId } } })),
    onSuccess: (saved) => {
      settle(saved)
      setError(undefined)
      toasts.success(t('screen.admin.failures.reopened'))
    },
    onError: (failure) => setError(problemMessage(failure, { module: 'scanning' }).text),
  })

  return (
    <div className="flex flex-col gap-5">
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <Section title={t('screen.admin.failures.operations')} empty={failures.operations.length === 0}>
        {failures.operations.map((operation) => (
          <li key={operation.id} className="flex flex-wrap items-start justify-between gap-3 p-4 text-body-sm">
            <div className="flex min-w-0 grow flex-col gap-0.5">
              <span className="font-mono text-caption break-all">{operation.type}</span>
              <span>{t('screen.admin.failures.operationLine', { error: operation.errorCode, attempts: operation.attempts })}</span>
              <span className="text-caption text-muted-foreground">
                {formatDayMonthTime(operation.failedAt)}
                {operation.jobId && (
                  <>
                    {' · '}
                    <Link to={`/quan-tri/cong-viec/${operation.jobId}`} className="text-primary underline-offset-4 hover:underline">
                      {t('screen.admin.failures.job')}
                    </Link>
                  </>
                )}
              </span>
            </div>
            <Button type="button" size="compact" onClick={() => setRetrying(operation.id)}>
              {t('screen.admin.failures.retry')}
            </Button>
          </li>
        ))}
      </Section>
      <Section title={t('screen.admin.failures.executions')} empty={failures.executions.length === 0}>
        {failures.executions.map((execution) => (
          <li key={execution.disputeId} className="flex flex-wrap items-start justify-between gap-3 p-4 text-body-sm">
            <div className="flex min-w-0 grow flex-col gap-0.5">
              <span>{t('screen.admin.failures.executionLine', { step: execution.step + 1 })}</span>
              <span className="text-caption text-muted-foreground break-words">
                {formatDayMonthTime(execution.failedAt)} · {execution.failure}
              </span>
            </div>
            <Link to={`/quan-tri/tranh-chap/${execution.disputeId}`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
              {t('screen.admin.failures.openDispute')}
            </Link>
          </li>
        ))}
      </Section>
      <Section title={t('screen.admin.failures.stalled')} empty={failures.stalledEvents.length === 0}>
        {failures.stalledEvents.map((event) => (
          <li key={event.id} className="flex flex-col gap-0.5 p-4 text-body-sm">
            <span className="font-mono text-caption break-all">{event.eventType}</span>
            <span className="text-caption text-muted-foreground break-all">
              {t('screen.admin.failures.stalledLine', { listener: event.listenerId, attempts: event.attempts })} ·{' '}
              {formatDayMonthTime(event.stalledAt)}
            </span>
          </li>
        ))}
      </Section>
      <Section title={t('screen.admin.failures.falsePositives')} empty={failures.falsePositives.length === 0}>
        {failures.falsePositives.map((item) => (
          <li key={item.findingId} className="flex flex-wrap items-start justify-between gap-3 p-4 text-body-sm">
            <div className="flex min-w-0 grow flex-col gap-0.5">
              <span className="text-label">
                {item.ruleType} · {item.productName}
              </span>
              <span className="font-mono text-caption break-all">{item.filePath}</span>
              <span className="break-words">{t('screen.admin.failures.reasonLine', { reason: item.reason })}</span>
              <span className="text-caption text-muted-foreground">
                {[item.markedByName, formatDayMonthTime(item.markedAt)].filter(Boolean).join(' · ')}
              </span>
            </div>
            <Button type="button" variant="secondary" size="compact" disabled={reopen.isPending} onClick={() => reopen.mutate(item.findingId)}>
              {t('screen.admin.failures.reopen')}
            </Button>
          </li>
        ))}
      </Section>
      <StepUpDialog
        open={retrying !== undefined}
        onOpenChange={(open) => !open && setRetrying(undefined)}
        confirmLabel={t('screen.admin.failures.retry')}
        pending={retry.isPending}
        onConfirm={(code, refused) => retrying && retry.mutate({ operationId: retrying, code }, { onError: refused })}
      />
    </div>
  )
}

function Section({ title, empty, children }: { title: string; empty: boolean; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-h2">{title}</h2>
      {empty ? (
        <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">{t('screen.admin.failures.empty')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">{children}</ul>
      )}
    </section>
  )
}
