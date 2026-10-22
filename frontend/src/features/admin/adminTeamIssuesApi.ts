import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'
import { OVERVIEW_QUERY_KEY } from './adminApi'

export type AdminTeamIssueRow = components['schemas']['AdminTeamIssueRow']
export type AdminTeamIssue = components['schemas']['AdminTeamIssueDetail']
export type CrewTermsEvidence = components['schemas']['CrewTermsEvidenceView']

const TEAM_ISSUES_QUERY_KEY = ['admin', 'team-issues'] as const

/** ADM-4's "Khiếu nại nội bộ": the escalated Team Issues (FR-79). */
export function useAdminTeamIssues() {
  return useQuery({
    queryKey: TEAM_ISSUES_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/team-issues')),
    retry: false,
  })
}

/** One escalated Team Issue with its split, its Rating for the assignee, and its decision. */
export function useAdminTeamIssue(teamIssueId: string | undefined) {
  return useQuery({
    queryKey: [...TEAM_ISSUES_QUERY_KEY, teamIssueId ?? ''],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/team-issues/{teamIssueId}', { params: { path: { teamIssueId: teamIssueId ?? '' } } }),
      ),
    enabled: teamIssueId !== undefined,
    retry: false,
  })
}

type Command =
  | { action: 'take' }
  | { action: 'decide'; body: { points?: number; removeRating?: boolean; reasoning: string; stepUpCode: string } }

/** "Nhận xử lý" or the decision after the step-up; the list, the detail, the evidence, and ADM-1 refresh. */
export function useTeamIssueCommand(teamIssueId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (command: Command) => {
      const path = { params: { path: { teamIssueId } } }
      return command.action === 'take'
        ? unwrap(apiClient.POST('/api/v1/admin/team-issues/{teamIssueId}/take', path))
        : unwrap(apiClient.POST('/api/v1/admin/team-issues/{teamIssueId}/decision', { ...path, body: command.body }))
    },
    onSuccess: (detail) => queryClient.setQueryData([...TEAM_ISSUES_QUERY_KEY, teamIssueId], detail),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: TEAM_ISSUES_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: ['admin', 'evidence'] }),
        queryClient.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY }),
      ]),
  })
}

/** The Team Evidence Package's Crew Terms versions, read under the assignee's grant (AD-20). */
export function useCrewTermsEvidence(jobId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['admin', 'evidence', 'crew-terms', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/jobs/{jobId}/crew-terms', { params: { path: { jobId } } })),
    enabled,
    retry: false,
  })
}

/** The Crew Chat, read under the same grant. */
export function useCrewChatEvidence(jobId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['admin', 'evidence', 'crew-chat', jobId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/crew-chat/messages', { params: { path: { boardId: jobId } } })),
    enabled,
    retry: false,
  })
}

/** The raiser's Work Items with their history, as far as the grant covers them. */
export function useWorkEvidence(jobId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['admin', 'evidence', 'work', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/work-items', { params: { path: { boardId: jobId } } })),
    enabled,
    retry: false,
  })
}
