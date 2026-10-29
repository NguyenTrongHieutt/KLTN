import { keepPreviousData, skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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

/** The last page of results the search received, which stays on screen when searching too fast (UX-DR27). */
function lastKey(jobId: string) {
  return [...KEY, 'last-search', jobId] as const
}

/** The last results the search received with the keyword and filter that found them. */
export interface LastMentorSearch {
  filter: MentorFilter
  found: MentorSearch
}

/**
 * The Job's Customer searches GitHub-verified users, 20 a page by relevance (FR-14, AD-36); no keyword finds no one.
 * The previous page stays while the next loads, and a refusal (a 4xx, such as `RATE_LIMITED` past 60 searches a
 * minute, shared with "Tìm việc") is not retried.
 */
export function useMentorSearch(jobId: string, filter: MentorFilter) {
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: [...KEY, 'search', jobId, filter],
    queryFn: async () => {
      const found = await unwrap(
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
      )
      queryClient.setQueryData<LastMentorSearch>(lastKey(jobId), { filter, found })
      return found
    },
    enabled: filter.q !== undefined,
    placeholderData: keepPreviousData,
    // Every search counts in the 60-a-minute window (AD-34): only the Customer's own Enter or "Tìm" runs one.
    refetchOnWindowFocus: false,
    retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 3,
  })
}

/** The last results {@link useMentorSearch} received for the Job, with their keyword, if any. */
export function useLastMentorSearch(jobId: string) {
  return useQuery<LastMentorSearch>({ queryKey: lastKey(jobId), queryFn: skipToken })
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

/** What a refusal of an invitation says the card's relation is, if it says one. */
function refusedRelation(error: unknown): MentorResult['relation'] | undefined {
  if (!(error instanceof ApiError)) {
    return undefined
  }
  return error.problem.reason === 'ALREADY_INVITED' ? 'INVITED'
    : error.problem.reason === 'INVITEE_ALREADY_APPLIED' ? 'APPLIED' : undefined
}

/**
 * "Mời chào giá" (FR-14, SG-41): no optimistic update; once the server answers, the quota and the Recommendations
 * read again and the card's relation changes in the results already on screen. The search itself is never sent again:
 * each search counts in the 60-a-minute window (AD-34).
 */
export function useInvite(jobId: string) {
  const queryClient = useQueryClient()
  const relate = (mentorId: string, relation: MentorResult['relation']) => {
    const mark = (found: MentorSearch | undefined) =>
      found && {
        ...found,
        items: found.items.map((item) => (item.accountId === mentorId ? { ...item, relation } : item)),
      }
    queryClient.setQueriesData<MentorSearch>({ queryKey: [...KEY, 'search', jobId] }, mark)
    queryClient.setQueryData<LastMentorSearch>(lastKey(jobId), (last) => last && { ...last, found: mark(last.found) ?? last.found })
  }
  return useMutation({
    mutationFn: (mentorId: string) =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/invitations', { params: { path: { jobId } }, body: { mentorId } })),
    onSuccess: (_, mentorId) => relate(mentorId, 'INVITED'),
    onError: (error, mentorId) => {
      const relation = refusedRelation(error)
      if (relation) {
        relate(mentorId, relation)
      }
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: [...KEY, 'job', jobId] }),
        queryClient.invalidateQueries({ queryKey: [...KEY, 'recommendations', jobId] }),
      ]),
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
