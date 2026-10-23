import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type PageShares = components['schemas']['PageSharesView']
export type PageShareOption = components['schemas']['PageShareOptionView']
export type SharedJob = components['schemas']['SharedJobView']

const sharesKey = (jobId: string) => ['page-shares', jobId] as const

/** "Chia sẻ tài liệu cũ" (FR-70): the Customer's checklist of the Product's other Jobs. */
export function usePageShares(jobId: string, enabled = true) {
  return useQuery({
    queryKey: sharesKey(jobId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/page-shares', { params: { path: { jobId } } })),
    enabled,
    retry: false,
  })
}

/** "Lưu chia sẻ": exactly the checked Jobs. */
export function useSavePageShares(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (jobIds: string[]) =>
      unwrap(apiClient.PUT('/api/v1/jobs/{jobId}/page-shares', { params: { path: { jobId } }, body: { jobIds } })),
    onSuccess: (saved) => queryClient.setQueryData(sharesKey(jobId), saved),
  })
}

/** The earlier Jobs shared with this Job, for its Mentor and active Crew; `NOT_FOUND` for anyone else. */
export function useSharedPages(jobId: string) {
  return useQuery({
    queryKey: ['shared-pages', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/shared-pages', { params: { path: { jobId } } })),
    retry: false,
  })
}
