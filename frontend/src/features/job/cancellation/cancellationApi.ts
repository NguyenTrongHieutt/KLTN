import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { MENTOR_JOB_KEY } from '@/features/matching/mentorJobApi'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Cancellation = components['schemas']['CancellationView']

/** JOB-1's "Hủy công việc" (FR-40); a Board's ID is its Job's. */
export function useCancellation(jobId: string) {
  return useQuery({
    queryKey: ['cancellation', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/cancellation', { params: { path: { boardId: jobId } } })),
    retry: false,
  })
}

type Command =
  | { action: 'propose'; scope: 'JOB' | 'REMAINING'; reason: string; version: number }
  | { action: 'confirm'; version: number }
  | { action: 'withdraw' }

/** Propose, confirm, or withdraw; each refreshes the section, the overview, the Job, and the to-dos. */
export function useCancellationCommand(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (command: Command) => {
      const path = { params: { path: { boardId: jobId } } }
      if (command.action === 'propose') {
        return unwrap(
          apiClient.POST('/api/v1/boards/{boardId}/cancellation', {
            ...path,
            body: { scope: command.scope, reason: command.reason, version: command.version },
          }),
        )
      }
      return command.action === 'confirm'
        ? unwrap(apiClient.POST('/api/v1/boards/{boardId}/cancellation/confirm', { ...path, body: { version: command.version } }))
        : unwrap(apiClient.POST('/api/v1/boards/{boardId}/cancellation/withdraw', path))
    },
    onSuccess: async (cancellation) => {
      queryClient.setQueryData(['cancellation', jobId], cancellation)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['progress', jobId] }),
        queryClient.invalidateQueries({ queryKey: JOBS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: MENTOR_JOB_KEY }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
  })
}
