import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'
import { OVERVIEW_QUERY_KEY } from './adminApi'

export type AdminSplitResolutionRow = components['schemas']['AdminSplitResolutionRow']
export type AdminSplitResolution = components['schemas']['AdminSplitResolutionDetail']
export type AdminSplitParty = components['schemas']['AdminSplitPartyView']

const QUERY_KEY = ['admin', 'split-resolutions'] as const

/** How often an open case reads again: no notice reports a statement, so the assignee learns of one here. */
const CASE_POLL_MS = 30_000

/** One party's whole percentage, as the Administrator typed it. */
export interface AdminSplitShare {
  partyId: string
  percent: number
}

/** ADM-4's "Phân chia tiền": every case, or only those undecided past their stored due time (UX-DR107). */
export function useAdminSplitResolutions(undecided: boolean) {
  return useQuery({
    queryKey: [...QUERY_KEY, 'list', undecided],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/split-resolutions', { params: { query: { undecided } } })),
    retry: false,
  })
}

/** One case: its words and its decision form are for its assignee only (A-18). It reads again while it is open. */
export function useAdminSplitResolution(resolutionId: string) {
  return useQuery({
    queryKey: [...QUERY_KEY, resolutionId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/admin/split-resolutions/{resolutionId}', { params: { path: { resolutionId } } })),
    retry: false,
    refetchInterval: (query) => (query.state.data?.status === 'OPEN' ? CASE_POLL_MS : false),
  })
}

type Command =
  { action: 'take' } | { action: 'decide'; body: { shares: AdminSplitShare[]; reasoning: string; stepUpCode: string } }

/**
 * "Nhận xử lý", whose answer holds none of the case's words, or the decision after the step-up; the list, the case, its
 * evidence and ADM-1 refresh.
 */
export function useSplitResolutionCommand(resolutionId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (command: Command) => {
      const path = { params: { path: { resolutionId } } }
      if (command.action === 'take') {
        await unwrap(apiClient.POST('/api/v1/admin/split-resolutions/{resolutionId}/take', path))
        return
      }
      client.setQueryData(
        [...QUERY_KEY, resolutionId],
        await unwrap(
          apiClient.POST('/api/v1/admin/split-resolutions/{resolutionId}/decision', { ...path, body: command.body }),
        ),
      )
    },
    onSettled: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: QUERY_KEY }),
        client.invalidateQueries({ queryKey: ['admin', 'split-evidence', resolutionId] }),
        client.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY }),
      ]),
  })
}

/** The server's VND lines of a decision that passes the live checks: the screen computes no money. */
export function useAdminSplitPreview(resolutionId: string, shares: AdminSplitShare[] | undefined) {
  return useQuery({
    queryKey: [...QUERY_KEY, resolutionId, 'preview', shares],
    queryFn: () =>
      unwrap(
        apiClient.POST('/api/v1/admin/split-resolutions/{resolutionId}/preview', {
          params: { path: { resolutionId } },
          body: { shares: shares ?? [] },
        }),
      ),
    enabled: shares !== undefined,
    retry: false,
  })
}

const evidenceKey = (resolutionId: string, tab: string) => ['admin', 'split-evidence', resolutionId, tab] as const

/** "Các bản phân chia": every version with its author, edits and confirmations, and each party's active interval. */
export function useSplitEvidenceVersions(resolutionId: string) {
  return useQuery({
    queryKey: evidenceKey(resolutionId, 'versions'),
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/split-resolutions/{resolutionId}/evidence/versions', {
          params: { path: { resolutionId } },
        }),
      ),
    retry: false,
  })
}

/** "Hạng mục": each party's Work Items of the Milestone with their histories. */
export function useSplitEvidenceWorkItems(resolutionId: string) {
  return useQuery({
    queryKey: evidenceKey(resolutionId, 'work-items'),
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/split-resolutions/{resolutionId}/evidence/work-items', {
          params: { path: { resolutionId } },
        }),
      ),
    retry: false,
  })
}

/** "Commit": the Handover Commits' authors by GitHub numeric ID, matched to the parties. */
export function useSplitEvidenceCommits(resolutionId: string) {
  return useQuery({
    queryKey: evidenceKey(resolutionId, 'commits'),
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/split-resolutions/{resolutionId}/evidence/commits', {
          params: { path: { resolutionId } },
        }),
      ),
    retry: false,
  })
}

/** "Trao đổi nhóm": the Crew Chat of the Milestone's interval. */
export function useSplitEvidenceChat(resolutionId: string) {
  return useQuery({
    queryKey: evidenceKey(resolutionId, 'crew-chat'),
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/split-resolutions/{resolutionId}/evidence/crew-chat', {
          params: { path: { resolutionId } },
        }),
      ),
    retry: false,
  })
}

/** "Tài khoản có thể cùng một người": pairs by kind, never the signal. */
export function useSplitEvidencePersonLinks(resolutionId: string) {
  return useQuery({
    queryKey: evidenceKey(resolutionId, 'person-links'),
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/split-resolutions/{resolutionId}/evidence/person-links', {
          params: { path: { resolutionId } },
        }),
      ),
    retry: false,
  })
}
