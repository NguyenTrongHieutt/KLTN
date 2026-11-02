import { Dialog } from '@base-ui/react/dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type ReactNode } from 'react'
import type { components } from '@/api/schema'
import { TermHint } from '@/components/TermHint'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { t, type MessageKey } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'

type Item = components['schemas']['RevocationItemView']
type Outcome = 'REMOVED' | 'ROTATED'

const checklistKey = (jobId: string) => ['revocation-checklist', jobId] as const

function useChecklist(jobId: string) {
  return useQuery({
    queryKey: checklistKey(jobId),
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/revocation-checklist', { params: { path: { boardId: jobId } } })),
    retry: false,
  })
}

/**
 * The Revocation Checklist (FR-66, UX-DR51, UX-DR55): when the Job ended, the Customer removes each access
 * they gave outside GitHub, with the identifiers the Mentor and the Crew entered and plain guidance, and
 * confirms it as "Đã gỡ quyền" or "Đã đổi khóa" after a short confirmation. Closure never waits for it.
 * The Mentor sees each item done or pending only. Nothing shows for a Job without External Access. A leaver's items are
 * {@link LeaverRevocations}'.
 */
export function RevocationChecklist({ jobId }: { jobId: string }) {
  const view = useChecklist(jobId).data
  const items = view?.items.filter((item) => !item.memberLogin) ?? []
  if (!view || items.length === 0) {
    return null
  }
  return view.audience === 'CUSTOMER' ? (
    <CustomerChecklist
      jobId={jobId}
      items={items}
      title={<TermHint term="revocationChecklist" />}
      lead={t('screen.closure.revocation.lead')}
    />
  ) : (
    <Steps items={items} />
  )
}

/**
 * The Customer's JOB-1 in every state (FR-66, R15, Story 21.6): each member who left has one item per External Access
 * item of the Agreement, identifier given or not, since the platform cannot know what the Customer granted; each is
 * ticked on its own, and a ticked one leaves the list. Nothing shows while none is open, and only the Customer reads them.
 */
export function LeaverRevocations({ jobId }: { jobId: string }) {
  const items = useChecklist(jobId).data?.items.filter((item) => item.memberLogin && !item.done) ?? []
  if (items.length === 0) {
    return null
  }
  return (
    <CustomerChecklist
      jobId={jobId}
      items={items}
      title={t('screen.closure.revocation.memberTitle')}
      lead={t('screen.closure.revocation.memberLead')}
    />
  )
}

/** The Mentor's view: the Customer's step per item, done or pending. */
function Steps({ items }: { items: Item[] }) {
  return (
    <ul className="flex flex-col gap-1.5 text-body-sm">
      {items.map((item) => (
        <li key={item.accessId} className="flex items-center gap-2">
          <span aria-hidden="true">{item.done ? '✓' : '○'}</span>
          <span className="break-words">{t('screen.closure.revocation.step', { system: item.system })}</span>
          <span className="text-caption text-muted-foreground">
            {t(item.done ? 'screen.closure.stepDone' : 'screen.closure.stepPending')}
          </span>
        </li>
      ))}
    </ul>
  )
}

function CustomerChecklist({
  jobId,
  items,
  title,
  lead,
}: {
  jobId: string
  items: Item[]
  title: ReactNode
  lead: string
}) {
  const ids = useId()
  const toasts = useToasts()
  const client = useQueryClient()
  const [asking, setAsking] = useState<{ item: Item; outcome: Outcome }>()
  const [error, setError] = useState<string>()
  const confirm = useMutation({
    mutationFn: ({ item, outcome }: { item: Item; outcome: Outcome }) =>
      unwrap(
        apiClient.POST('/api/v1/boards/{boardId}/revocation-checklist/{accessId}', {
          params: { path: { boardId: jobId, accessId: item.accessId } },
          body: { outcome, memberLogin: item.memberLogin },
        }),
      ),
    onSuccess: async (next) => {
      client.setQueryData(checklistKey(jobId), next)
      await client.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
  })
  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-3 rounded-md bg-muted p-3 text-body-sm">
      <h3 id={`${ids}-title`} className="text-h3">
        {title}
      </h3>
      <p>{lead}</p>
      <ul className="flex flex-col gap-3">
        {items.map((item) => (
          <li
            key={`${item.accessId}-${item.memberLogin ?? ''}`}
            className="flex flex-col gap-1.5 rounded-md border border-border bg-card p-3"
          >
            <p className="font-semibold break-words">
              {item.memberLogin
                ? t('screen.closure.revocation.memberItem', { system: item.system, login: item.memberLogin })
                : item.system}{' '}
              <span className="text-caption font-normal text-muted-foreground">
                {t(`screen.closure.revocation.environment.${item.environment}` as MessageKey)} ·{' '}
                {t(`screen.closure.revocation.level.${item.accessLevel}` as MessageKey)}
              </span>
            </p>
            {item.identifiers.length > 0 ? (
              <div>
                <p>{t('screen.closure.revocation.identifiers')}</p>
                <ul className="flex flex-col gap-0.5">
                  {item.identifiers.map((identifier) => (
                    <li key={`${identifier.githubLogin}-${identifier.identifier}`}>
                      <code className="font-mono text-caption break-all">{identifier.identifier}</code>{' '}
                      <span className="text-caption text-muted-foreground">(@{identifier.githubLogin})</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              <p>{t('screen.closure.revocation.noIdentifiers')}</p>
            )}
            <p className="text-muted-foreground">{t('screen.closure.revocation.guidance', { system: item.system })}</p>
            {item.done && item.confirmedAt ? (
              <p className="text-caption text-success">
                {t(item.outcome === 'ROTATED' ? 'screen.closure.revocation.doneRotated' : 'screen.closure.revocation.doneRemoved', {
                  time: formatDayMonthTime(item.confirmedAt),
                })}
              </p>
            ) : (
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="compact" onClick={() => setAsking({ item, outcome: 'REMOVED' })}>
                  {t('screen.closure.revocation.removed')}
                </Button>
                <Button
                  type="button"
                  size="compact"
                  variant="secondary"
                  onClick={() => setAsking({ item, outcome: 'ROTATED' })}
                >
                  {t('screen.closure.revocation.rotated')}
                </Button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      <Dialog.Root open={asking !== undefined} onOpenChange={(next) => !next && setAsking(undefined)}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2">
              {asking && t('screen.closure.revocation.confirmTitle', { system: asking.item.system })}
            </Dialog.Title>
            <Dialog.Description className="text-body-sm">
              {asking &&
                t(asking.outcome === 'ROTATED' ? 'screen.closure.revocation.rotated' : 'screen.closure.revocation.removed')}
              . {t('screen.closure.revocation.confirmBody')}
            </Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setAsking(undefined)}>
                {t('screen.closure.revocation.cancel')}
              </Button>
              <Button
                type="button"
                disabled={confirm.isPending}
                onClick={() =>
                  asking &&
                  confirm.mutate(
                    { item: asking.item, outcome: asking.outcome },
                    {
                      onSuccess: () => {
                        setAsking(undefined)
                        setError(undefined)
                        toasts.success(t('screen.closure.revocation.saved'))
                      },
                      onError: (failure) => {
                        setAsking(undefined)
                        setError(problemMessage(failure, { module: 'repo' }).text)
                      },
                    },
                  )
                }
              >
                {t('screen.closure.revocation.confirm')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}
