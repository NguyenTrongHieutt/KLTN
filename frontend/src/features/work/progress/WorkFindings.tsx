import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ShieldAlert } from 'lucide-react'
import type { components } from '@/api/schema'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'

type JobFinding = components['schemas']['JobFindingView']

const findingsKey = (jobId: string) => ['job', 'findings', jobId] as const

/**
 * The Job's open Findings (FR-8, UX-DR31): what the push scans found in the Working Repository, masked,
 * for the Customer, the Mentor, and the Crew. While any is open the next handover waits. A credential's
 * resolver, the Mentor, confirms "Đã đổi khóa". Nothing shows when there is none or the Job has none.
 */
export function WorkFindings({ jobId }: { jobId: string }) {
  const client = useQueryClient()
  const findings = useQuery({
    queryKey: findingsKey(jobId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/findings', { params: { path: { jobId } } })),
    retry: false,
    refetchInterval: 30_000,
  })
  const rotate = useMutation({
    mutationFn: (findingId: string) =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/findings/{findingId}/rotation', { params: { path: { jobId, findingId } } }),
      ),
    onSuccess: (open) => client.setQueryData(findingsKey(jobId), open),
  })
  if (!findings.data || findings.data.length === 0) {
    return null
  }
  return (
    <section aria-labelledby="work-findings" className="flex flex-col gap-3 rounded-lg border border-destructive bg-card p-4">
      <h2 id="work-findings" className="flex items-center gap-2 text-h3">
        <ShieldAlert aria-hidden="true" className="size-5 text-destructive" />
        {t('screen.progress.findings.title', { n: findings.data.length })}
      </h2>
      <p className="text-body-sm">{t('screen.progress.findings.lead')}</p>
      <ul className="flex flex-col divide-y divide-border">
        {findings.data.map((finding: JobFinding) => (
          <li key={finding.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-body-sm">
            <div className="flex min-w-0 flex-col gap-0.5">
              <span className="font-semibold">{finding.type}</span>
              <span className="text-caption text-muted-foreground break-all">
                {t('screen.progress.findings.where', { path: finding.path, commit: finding.commit })}
              </span>
              <code className="text-caption break-all">{finding.maskedPreview}</code>
            </div>
            {finding.canRotate && (
              <Button variant="secondary" size="compact" disabled={rotate.isPending} onClick={() => rotate.mutate(finding.id)}>
                {t('screen.progress.findings.rotated')}
              </Button>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
