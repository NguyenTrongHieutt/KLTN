import { useQuery } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * ADM-8 (`/quan-tri/nhat-ky`, FR-63, SG-28): the read-only Audit Log, newest first, by Job or by user,
 * with the actor, the time, the action, and the affected objects.
 */
export function AdminAuditPage() {
  usePageTitle(t('screen.admin.audit.title'))
  const [params, setParams] = useSearchParams()
  const jobId = params.get('jobId') ?? undefined
  const accountId = params.get('accountId') ?? undefined
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1) - 1
  const [job, setJob] = useState(jobId ?? '')
  const [account, setAccount] = useState(accountId ?? '')
  const [error, setError] = useState<string>()
  const ids = useId()
  const entries = useQuery({
    queryKey: ['admin', 'audit', jobId, accountId, page],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/audit', { params: { query: { jobId, accountId, page } } })),
    retry: false,
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = new URLSearchParams()
    for (const [key, value] of [
      ['jobId', job.trim()],
      ['accountId', account.trim()],
    ]) {
      if (value === '') continue
      if (!UUID.test(value)) {
        setError(t('screen.admin.audit.badId'))
        return
      }
      next.set(key, value)
    }
    setError(undefined)
    setParams(next)
  }

  const pages = Math.max(1, Math.ceil((entries.data?.total ?? 0) / (entries.data?.size ?? 50)))
  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.audit.heading')}</PageHeading>
      <form role="search" onSubmit={submit} className="flex flex-wrap items-end gap-3">
        <div className="flex min-w-0 grow flex-col gap-1">
          <label htmlFor={`${ids}-job`} className="text-label">
            {t('screen.admin.audit.job')}
          </label>
          <input id={`${ids}-job`} value={job} onChange={(event) => setJob(event.target.value)} className={inputClass} />
        </div>
        <div className="flex min-w-0 grow flex-col gap-1">
          <label htmlFor={`${ids}-account`} className="text-label">
            {t('screen.admin.audit.account')}
          </label>
          <input id={`${ids}-account`} value={account} onChange={(event) => setAccount(event.target.value)} className={inputClass} />
        </div>
        <Button type="submit">{t('screen.admin.audit.filter')}</Button>
      </form>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <RegionState query={entries} skeleton={<Skeleton className="h-64 w-full" />}>
        {entries.data &&
          (entries.data.items.length === 0 ? (
            <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">{t('screen.admin.audit.empty')}</p>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-border bg-card">
              <table className="w-full text-left text-body-sm">
                <caption className="sr-only">{t('screen.admin.audit.caption')}</caption>
                <thead className="border-b border-border text-caption text-muted-foreground">
                  <tr>
                    <th scope="col" className="p-3">
                      {t('screen.admin.audit.time')}
                    </th>
                    <th scope="col" className="p-3">
                      {t('screen.admin.audit.actor')}
                    </th>
                    <th scope="col" className="p-3">
                      {t('screen.admin.audit.action')}
                    </th>
                    <th scope="col" className="p-3">
                      {t('screen.admin.audit.objects')}
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {entries.data.items.map((entry) => (
                    <tr key={entry.id}>
                      <td className="p-3 whitespace-nowrap tabular-nums">{formatDayMonthTime(entry.occurredAt)}</td>
                      <td className="p-3">{entry.system ? t('screen.admin.audit.system') : (entry.actorName ?? entry.actorAccountId)}</td>
                      <td className="p-3 font-mono text-caption break-all">{entry.action}</td>
                      <td className="p-3 font-mono text-caption break-all">
                        {entry.objects.map((object) => `${object.type}:${object.id.slice(0, 8)}`).join(', ')}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
      </RegionState>
      {pages > 1 && (
        <nav aria-label={t('screen.admin.audit.pagination')} className="flex items-center justify-between gap-2">
          <Button
            variant="secondary"
            size="compact"
            disabled={page === 0}
            onClick={() => {
              const next = new URLSearchParams(params)
              next.set('page', `${page}`)
              setParams(next)
            }}
          >
            {t('screen.admin.audit.previous')}
          </Button>
          <span className="text-caption">{t('screen.admin.audit.page', { page: page + 1, pages })}</span>
          <Button
            variant="secondary"
            size="compact"
            disabled={page + 1 >= pages}
            onClick={() => {
              const next = new URLSearchParams(params)
              next.set('page', `${page + 2}`)
              setParams(next)
            }}
          >
            {t('screen.admin.audit.next')}
          </Button>
        </nav>
      )}
    </div>
  )
}
