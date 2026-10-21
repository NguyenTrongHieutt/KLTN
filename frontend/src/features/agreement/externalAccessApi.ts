import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type ExternalAccess = components['schemas']['ExternalAccessView']
export type ExternalAccessItem = components['schemas']['ExternalAccessItemView']

const key = (jobId: string) => ['external-access', jobId] as const

/** FR-66 after confirmation: the Customer's guidance with the identifiers, or the caller's own; a 404 before it. */
export function useExternalAccess(jobId: string) {
  return useQuery({
    queryKey: key(jobId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/external-access', { params: { path: { jobId } } })),
    retry: false,
  })
}

/** The Mentor's or a Crew member's own account on one item's system; an empty one clears it. */
export function useSaveIdentifier(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ accessId, identifier }: { accessId: string; identifier: string }) =>
      unwrap(
        apiClient.PUT('/api/v1/jobs/{jobId}/external-access/{accessId}/identifier', {
          params: { path: { jobId, accessId } },
          body: { identifier },
        }),
      ),
    onSuccess: (view) => queryClient.setQueryData(key(jobId), view),
  })
}

/** "Đã cấp quyền": the Customer's to-do for the item closes. */
export function useGrantAccess(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (accessId: string) =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/external-access/{accessId}/granted', { params: { path: { jobId, accessId } } }),
      ),
    onSuccess: (view) => {
      queryClient.setQueryData(key(jobId), view)
      void queryClient.invalidateQueries({ queryKey: [...NOTIFICATION_QUERY_KEY, 'todos'] })
    },
  })
}
