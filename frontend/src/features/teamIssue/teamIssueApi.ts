import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type TeamIssuePanel = components['schemas']['TeamIssuePanelView']
export type TeamIssue = components['schemas']['TeamIssueView']
export type TeamIssueShareOption = components['schemas']['TeamIssueShareOptionView']
export type TeamIssueRatingOption = components['schemas']['TeamIssueRatingOptionView']
export type RaiseTeamIssue = components['schemas']['RaiseTeamIssueRequest']

const PANEL_KEY = ['team-issues', 'panel'] as const
const jobKey = (jobId: string) => ['team-issues', 'job', jobId] as const

/** STU-4's "Khiếu nại nội bộ" (FR-79): per Job, what the Student may raise and their issues. */
export function useTeamIssuePanel() {
  return useQuery({
    queryKey: PANEL_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/team-issues')),
    retry: false,
  })
}

/** "Gửi khiếu nại": the panel reloads either way, since a refusal may mean it changed. */
export function useRaiseTeamIssue() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: RaiseTeamIssue) => unwrap(apiClient.POST('/api/v1/team-issues', { body })),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: PANEL_KEY })
      await queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
  })
}

/** JOB-1: the Job's Team Issues for its Mentor (AD-40); nobody else is asked. */
export function useJobTeamIssues(jobId: string) {
  return useQuery({
    queryKey: jobKey(jobId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/team-issues', { params: { path: { boardId: jobId } } })),
    retry: false,
  })
}
