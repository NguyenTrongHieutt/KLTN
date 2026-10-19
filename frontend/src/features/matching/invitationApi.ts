import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { ApiError, apiClient, unwrap } from '@/lib/apiClient'

export type MentorResult = components['schemas']['MentorResultView']
export type MentorSearch = components['schemas']['MentorSearchView']
export type Invitations = components['schemas']['InvitationsView']
export type InviteLimit = NonNullable<Invitations['limit']>
export type MentorInvitation = components['schemas']['MentorInvitationView']
export type MentorSummary = components['schemas']['MentorSummaryView']

/** CUS-8 "Tìm người hướng dẫn"'s keyword and Skill filter, as the URL keeps them (FR-14). */
export interface MentorFilter {
  q?: string
  skills: string[]
  categories: string[]
  /** From 0. */
  page: number
}

const KEY = ['invitation'] as const

/**
 * The Job's Customer searches Mentors, 20 a page by relevance (FR-14); no keyword finds no one. The
 * previous page stays while the next loads, and a refusal is not retried.
 */
export function useMentorSearch(jobId: string, filter: MentorFilter) {
  return useQuery({
    queryKey: [...KEY, 'search', jobId, filter],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/jobs/{jobId}/mentors', {
          params: {
            path: { jobId },
            query: {
              q: filter.q ?? '',
              skill: filter.skills.length > 0 ? filter.skills : undefined,
              category: filter.categories.length > 0 ? filter.categories : undefined,
              page: filter.page,
            },
          },
        }),
      ),
    enabled: filter.q !== undefined,
    placeholderData: keepPreviousData,
    retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 3,
  })
}

/** The page's Rating summaries in one call (SG-43): never one request per card. */
export function useMentorSummaries(ids: string[]) {
  return useQuery({
    queryKey: ['reputation', 'mentor-summaries', ids],
    queryFn: () => unwrap(apiClient.GET('/api/v1/reputation/mentor-summaries', { params: { query: { id: ids } } })),
    enabled: ids.length > 0,
  })
}

/** The Customer's quota for the Job today and the invitations they sent (UX-DR49). */
export function useInvitations(jobId: string) {
  return useQuery({
    queryKey: [...KEY, 'job', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/invitations', { params: { path: { jobId } } })),
    retry: false,
  })
}

/**
 * "Mời ứng tuyển" (FR-14): no optimistic update; the cards and the quota change once the server
 * confirms, and a refusal refreshes them too.
 */
export function useInvite(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (mentorId: string) =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/invitations', { params: { path: { jobId } }, body: { mentorId } })),
    onSettled: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/** MEN-3: the Mentor's own invitation to this Job, or none (a 404 reads as none). */
export function useMentorInvitation(postId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'mine', postId],
    queryFn: async () => {
      try {
        return await unwrap(
          apiClient.GET('/api/v1/mentor/job-posts/{postId}/invitation', { params: { path: { postId: postId ?? '' } } }),
        )
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return null
        }
        throw error
      }
    },
    enabled: postId !== undefined,
    retry: false,
  })
}
