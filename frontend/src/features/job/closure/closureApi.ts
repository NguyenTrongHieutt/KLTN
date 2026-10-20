import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { MENTOR_JOB_KEY } from '@/features/matching/mentorJobApi'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Closure = components['schemas']['ClosureView']

/** JOB-1's closure section (FR-37, FR-67, UX-DR55); a Board's ID is its Job's. */
export function useClosure(jobId: string | undefined) {
  return useQuery({
    queryKey: ['closure', jobId ?? ''],
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/closure', { params: { path: { boardId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/**
 * "Đề nghị kết thúc", "Xác nhận kết thúc", or "Từ chối" on the loaded Job version (AD-23); each refreshes
 * the section, the overview, the Job, and the to-dos.
 */
export function useClosureCommand(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, version }: { action: 'propose' | 'confirm' | 'decline'; version: number }) => {
      const params = { params: { path: { boardId: jobId } }, body: { version } }
      if (action === 'propose') {
        return unwrap(apiClient.POST('/api/v1/boards/{boardId}/closure', params))
      }
      return action === 'confirm'
        ? unwrap(apiClient.POST('/api/v1/boards/{boardId}/closure/confirm', params))
        : unwrap(apiClient.POST('/api/v1/boards/{boardId}/closure/decline', params))
    },
    onSuccess: async (closure) => {
      queryClient.setQueryData(['closure', jobId], closure)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['progress', jobId] }),
        queryClient.invalidateQueries({ queryKey: JOBS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: MENTOR_JOB_KEY }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
  })
}
