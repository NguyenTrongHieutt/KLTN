import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type FollowUps = components['schemas']['FollowUpsView']
export type FollowUp = components['schemas']['FollowUpView']
type FollowUpRequest = components['schemas']['FollowUpRequest']

/** JOB-1's follow-up Jobs (FR-11): the accepted Report's recommendations and the drafts. */
export function useFollowUps(jobId: string) {
  return useQuery({
    queryKey: ['follow-ups', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/follow-ups', { params: { path: { boardId: jobId } } })),
    retry: false,
  })
}

type Command =
  | { action: 'create'; content: FollowUpRequest }
  | { action: 'update'; followUpId: string; content: FollowUpRequest }
  | { action: 'delete'; followUpId: string }
  | { action: 'submit' }
  | { action: 'open'; followUpId: string }
  | { action: 'discard'; followUpId: string }

/**
 * The Mentor's drafting and "Gửi khách hàng", and the Customer's "Mở để đăng tin" and "Bỏ qua"; "Mở"
 * answers the new Draft's ID for CUS-6, every other command the section.
 */
export function useFollowUpCommand(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (command: Command) => {
      const board = { params: { path: { boardId: jobId } } }
      switch (command.action) {
        case 'create':
          return unwrap(apiClient.POST('/api/v1/boards/{boardId}/follow-ups', { ...board, body: command.content }))
        case 'update':
          return unwrap(
            apiClient.PUT('/api/v1/follow-ups/{followUpId}', {
              params: { path: { followUpId: command.followUpId } },
              body: command.content,
            }),
          )
        case 'delete':
          return unwrap(
            apiClient.DELETE('/api/v1/follow-ups/{followUpId}', { params: { path: { followUpId: command.followUpId } } }),
          )
        case 'submit':
          return unwrap(apiClient.POST('/api/v1/boards/{boardId}/follow-ups/submit', board))
        case 'open':
          return unwrap(
            apiClient.POST('/api/v1/follow-ups/{followUpId}/open', { params: { path: { followUpId: command.followUpId } } }),
          )
        case 'discard':
          return unwrap(
            apiClient.POST('/api/v1/follow-ups/{followUpId}/discard', {
              params: { path: { followUpId: command.followUpId } },
            }),
          )
      }
    },
    onSuccess: async (result) => {
      if (result && 'followUps' in result) {
        queryClient.setQueryData(['follow-ups', jobId], result)
      } else {
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: ['follow-ups', jobId] }),
          queryClient.invalidateQueries({ queryKey: JOBS_QUERY_KEY }),
        ])
      }
      await queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
  })
}
