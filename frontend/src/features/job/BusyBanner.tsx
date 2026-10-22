import { useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { useEffect, useRef } from 'react'
import type { components } from '@/api/schema'
import { useToasts } from '@/components/toasts'
import { t, type MessageKey } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { busyKey } from './jobBusy'

type Busy = components['schemas']['BusyView']

/** How often the banner reads the marker again while it is set (UX-DR21). */
const BUSY_POLL_MS = 3_000

/**
 * The busy banner on JOB-H (UX-DR21, AD-28): while a precondition operation holds the Job, such as the
 * merge of a Code Milestone's acceptance, an info line "Đang hợp nhất bàn giao Giai đoạn {n}…", read
 * again every 3 s and announced politely. When it clears, a toast states the outcome, read from the
 * handover's report, and the Job's screens refresh. Setup's own stages show in the step tracker instead.
 */
export function BusyBanner({ jobId }: { jobId: string }) {
  const client = useQueryClient()
  const toasts = useToasts()
  const busy = useQuery({
    queryKey: busyKey(jobId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/busy', { params: { path: { jobId } } })),
    retry: false,
    refetchInterval: (query) => (query.state.data?.busy ? BUSY_POLL_MS : false),
  })
  const previous = useRef<Busy | undefined>(undefined)
  useEffect(() => {
    const view = busy.data
    if (!view) {
      return
    }
    const before = previous.current
    previous.current = view
    if (!before?.busy || view.busy) {
      return
    }
    void (async () => {
      const outcome = await announce(before, jobId)
      if (outcome.error) {
        toasts.error(outcome.text)
      } else {
        toasts.success(outcome.text)
      }
      await Promise.all([
        client.invalidateQueries({ queryKey: ['job'] }),
        client.invalidateQueries({ queryKey: ['handover'] }),
        client.invalidateQueries({ queryKey: ['handovers', jobId] }),
        client.invalidateQueries({ queryKey: ['progress', jobId] }),
        client.invalidateQueries({ queryKey: ['final-merge', jobId] }),
        client.invalidateQueries({ queryKey: ['closure', jobId] }),
      ])
    })()
  }, [busy.data, client, jobId, toasts])

  const view = busy.data
  if (!view?.busy || view.purpose === 'JOB_SETUP' || view.purpose === 'JOB_VERIFY') {
    return null
  }
  return (
    <p
      role="status"
      aria-live="polite"
      className="flex items-center gap-2 rounded-md border-l-4 border-primary bg-primary-soft p-3 text-body-sm"
    >
      <LoaderCircle aria-hidden="true" className="size-4 shrink-0 animate-spin text-primary motion-reduce:animate-none" />
      {t(`screen.job.busy.${banner(view)}` as MessageKey, { n: view.milestone ?? '' })}
    </p>
  )
}

/** The Final Merge reads as the Customer's own project to them, and as the last merge to the team. */
function banner(view: Busy): string {
  return view.purpose === 'FINAL_MERGE' && view.audience === 'CUSTOMER' ? 'FINAL_MERGE_CUSTOMER' : (view.purpose ?? '')
}

/** What the cleared marker came to: an acceptance reads its handover's report, a Final Merge its step. */
async function announce(before: Busy, jobId: string): Promise<{ text: string; error: boolean }> {
  const done = { text: t('screen.job.busy.done'), error: false }
  if (before.purpose === 'FINAL_MERGE' && before.audience === 'CUSTOMER') {
    try {
      const merge = await unwrap(apiClient.GET('/api/v1/jobs/{jobId}/final-merge', { params: { path: { jobId } } }))
      return merge.merge?.status === 'MERGED' ? { text: t('screen.job.busy.mergedCustomer'), error: false } : done
    } catch {
      return done
    }
  }
  if (before.purpose !== 'ACCEPTANCE' || !before.handoverId) {
    return done
  }
  try {
    const report = await unwrap(
      apiClient.GET('/api/v1/handovers/{handoverId}', { params: { path: { handoverId: before.handoverId } } }),
    )
    if (report.status === 'ACCEPTED') {
      return { text: t('screen.handover.check.accepted', { n: report.number }), error: false }
    }
    if (report.status === 'VOIDED') {
      return { text: t('reason.delivery.HANDOVER_VOIDED'), error: true }
    }
    if (report.acceptance === 'FAILED') {
      return { text: t('screen.handover.acceptance.failed'), error: true }
    }
    return done
  } catch {
    return done
  }
}
