import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { MENTOR_JOB_KEY } from '@/features/matching/mentorJobApi'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { ApiError, apiClient, unwrap } from '@/lib/apiClient'

export type Cancellation = components['schemas']['CancellationView']
export type CancellationProposal = components['schemas']['CancellationProposalView']

/** JOB-1's "Hủy công việc" (FR-40); the path carries the Job's ID. */
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
  | { action: 'alone'; reason: string; version: number }
  | { action: 'withdraw' }
  | { action: 'answer'; proposalId: string; agree: boolean }

/**
 * Propose, confirm, or withdraw; each refreshes the section, the overview, the Job, and the to-dos. A counted member of
 * a Self-mentored Job answers a proposal instead (Story 25.7), naming the proposal shown. A `STALE` answer reads the
 * cancellation again, so "thử lại" sends the Job's new version, not the one just refused.
 */
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
      if (command.action === 'answer') {
        return unwrap(
          apiClient.POST('/api/v1/boards/{boardId}/cancellation/answer', {
            ...path,
            body: { proposalId: command.proposalId, agree: command.agree },
          }),
        )
      }
      if (command.action === 'alone') {
        return unwrap(
          apiClient.POST('/api/v1/boards/{boardId}/cancellation/alone', {
            ...path,
            body: { reason: command.reason, version: command.version },
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
    onError: (error) => {
      if (error instanceof ApiError && error.problem.code === 'STALE') {
        return queryClient.invalidateQueries({ queryKey: ['cancellation', jobId] })
      }
    },
  })
}
