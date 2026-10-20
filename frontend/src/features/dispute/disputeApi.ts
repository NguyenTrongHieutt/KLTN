import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type JobDispute = components['schemas']['JobDisputeView']

/** JOB-1's Dispute (FR-52); a Board's ID is its Job's. */
export function useJobDispute(jobId: string) {
  return useQuery({
    queryKey: ['dispute', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/dispute', { params: { path: { boardId: jobId } } })),
    retry: false,
  })
}
