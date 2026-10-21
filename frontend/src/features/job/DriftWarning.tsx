import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { TriangleAlert } from 'lucide-react'
import type { components } from '@/api/schema'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { PauseSyncHelp } from './PauseSyncHelp'

type Drift = components['schemas']['DriftView']

const driftKey = (jobId: string) => ['job', 'drift', jobId] as const

/**
 * The Drift Warning on JOB-H and JOB-1 (FR-20, UX-DR27): a non-dismissible warning alert while the Source
 * Repository changed during the Job, saying what changed, that the Final Merge might fail and the code
 * would then come as a ZIP, with "Cách tạm dừng đồng bộ". The Customer re-confirms a renamed or
 * transferred repository here. Nothing shows without a warning or on a Job without one.
 */
export function DriftWarning({ jobId }: { jobId: string }) {
  const client = useQueryClient()
  const drift = useQuery({
    queryKey: driftKey(jobId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/drift', { params: { path: { jobId } } })),
    retry: false,
    staleTime: 60_000,
  })
  const reconfirm = useMutation({
    mutationFn: () => unwrap(apiClient.POST('/api/v1/jobs/{jobId}/source/reconfirm', { params: { path: { jobId } } })),
    onSuccess: (view: Drift) => client.setQueryData(driftKey(jobId), view),
  })
  const warnings = drift.data?.warnings ?? []
  if (warnings.length === 0) {
    return null
  }
  const latest = warnings[warnings.length - 1]
  return (
    <section role="alert" className="flex flex-col gap-2 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <p className="flex items-start gap-2 text-body-sm font-semibold">
        <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
        {t('screen.job.drift.title')}
      </p>
      <p className="text-body-sm">
        {t(`screen.job.drift.kind${latest.kind}` as MessageKey, {
          n: latest.commits,
          name: latest.fullName ?? '',
          date: formatDayMonth(latest.detectedAt),
        })}
      </p>
      <p className="text-body-sm">{t('screen.job.drift.meaning')}</p>
      <PauseSyncHelp />
      {drift.data?.reconfirmNeeded && (
        <div className="flex flex-col gap-2">
          <p className="text-body-sm">{t('screen.job.drift.reconfirmLead')}</p>
          <Button className="w-fit" disabled={reconfirm.isPending} onClick={() => reconfirm.mutate()}>
            {t('screen.job.drift.reconfirm')}
          </Button>
          {reconfirm.isError && (
            <p className="text-body-sm text-destructive">
              {problemMessage(reconfirm.error, { module: 'repo', action: t('screen.job.drift.reconfirmAction') }).text}
            </p>
          )}
        </div>
      )}
    </section>
  )
}
