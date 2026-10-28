import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { t, type MessageKey } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { JOBS_QUERY_KEY } from './jobApi'

/** The caller's role in one Job (AD-43): `CUSTOMER`, `MENTOR`, or `CREW` with its Crew relationship. */
export type JobRoleView = components['schemas']['JobRoleView']
export type RoleInJob = JobRoleView['role']
export type CrewKind = NonNullable<JobRoleView['crewKind']>

/** One Job of "Công việc" (CUS-7) with the caller's role in it. */
export type MyJob = components['schemas']['MyJobView']

/**
 * The caller's role in the Job (UX-DR87, Story 20.6): every Job screen picks its Customer, Mentor, or Crew variant
 * from it, and JOB-H says "Bạn là: …". A Job where the caller holds no role answers `NOT_FOUND`.
 */
export function useJobRole(jobId: string | undefined) {
  return useQuery({
    queryKey: [...JOBS_QUERY_KEY, 'role', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/role', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
    staleTime: 30_000,
  })
}

/** CUS-7 (UX-DR86): every Job where the caller is the Customer, the Mentor, or a Crew member, newest first. */
export function useMyJobs() {
  return useQuery({
    queryKey: [...JOBS_QUERY_KEY, 'mine'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/my-jobs')),
  })
}

const ROLE_LABEL: Record<RoleInJob, MessageKey> = {
  CUSTOMER: 'screen.job.role.CUSTOMER',
  MENTOR: 'screen.job.role.MENTOR',
  CREW: 'screen.job.role.CREW',
}

/** The R16 word for a role in a Job (UX-DR109): "Khách hàng", "Người hướng dẫn", "Thành viên nhóm". */
export function roleLabel(role: RoleInJob): string {
  return t(ROLE_LABEL[role])
}

/** JOB-H's read-only line (UX-DR87): "Bạn là: Thành viên nhóm · đã rời" for a former member. */
export function youAreText(view: { role: RoleInJob; crewKind?: CrewKind | null }): string {
  const role = roleLabel(view.role)
  return view.crewKind === 'FORMER'
    ? t('screen.job.role.youAreFormer', { role })
    : t('screen.job.role.youAre', { role })
}
