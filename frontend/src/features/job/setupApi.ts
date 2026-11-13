import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import vi from '@/i18n/vi.json'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Setup = components['schemas']['SetupView']

/** How often the step tracker reads the Setup while the Job is Starting (UX-DR23, UX-DR114). */
export const SETUP_POLL_MS = 3000

/**
 * N-32's version, which a self-confirmation names: the server's `vibefix.repo.self-confirmation-notice-version`,
 * mirrored in `vi.json` as the product notices are.
 */
export const SELF_CONFIRMATION_NOTICE_VERSION = (vi as { notice: { selfConfirmation: { version: string } } }).notice
  .selfConfirmation.version

export const setupKey = (jobId: string) => ['job', 'setup', jobId] as const

/** JOB-H's step tracker: the Setup's steps, polled every 3 s. */
export function useSetup(jobId: string | undefined) {
  return useQuery({
    queryKey: setupKey(jobId ?? ''),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/setup', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    refetchInterval: SETUP_POLL_MS,
    retry: false,
  })
}

/** "Thử lại" by the Customer after a block. */
export function useRetrySetup(jobId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.POST('/api/v1/jobs/{jobId}/setup/retry', { params: { path: { jobId } } })),
    onSuccess: (setup) => client.setQueryData(setupKey(jobId), setup),
  })
}

/**
 * The Customer's J7 self-confirmation (N-32, R4): the head the SHA confirmation card showed, with N-32's version. A
 * head that moved in between answers `STALE`, and the Setup then holds the new head.
 */
export function useConfirmSourceClean(jobId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (sha: string) =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/setup/confirmation', {
          params: { path: { jobId } },
          body: { sha, noticeVersion: SELF_CONFIRMATION_NOTICE_VERSION },
        }),
      ),
    onSuccess: (setup) => client.setQueryData(setupKey(jobId), setup),
  })
}
