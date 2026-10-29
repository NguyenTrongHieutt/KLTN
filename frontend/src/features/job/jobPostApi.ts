import { keepPreviousData, skipToken, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { ApiError, apiClient, unwrap } from '@/lib/apiClient'
import type { Budget, JobType } from './jobApi'

export type JobPostSummary = components['schemas']['JobPostSummaryView']
export type JobPost = components['schemas']['JobPostView']
export type MentorCapacity = components['schemas']['CapacityView']
export type CapacityReason = MentorCapacity['reasons'][number]
export type PostCustomerRating = components['schemas']['PostCustomerRatingView']

/** A listed Job's phase (§4.0, X6): a label `job.phaseOf` derives, never a state. */
export type JobPhase = NonNullable<JobPostSummary['phase']>

/** What a listed Job needs: a Mentor while it takes bids, Crew members while its Mentor recruits. */
export type JobNeed = JobPostSummary['need']

/** The three phases "Tình trạng" filters by (UX-DR97). */
export type ListedPhase = Exclude<JobPhase, 'AGREEING'>

/** MEN-2's keyword and filters, as the URL keeps them (FR-12). */
export interface JobPostFilter {
  /** The keyword; results then rank by relevance (AD-36). */
  q?: string
  /** "Tình trạng". */
  phase?: ListedPhase
  type?: JobType
  skills: string[]
  categories: string[]
  budget?: Budget
  /** Due within this many days. */
  within?: 14 | 30
  /** From 0. */
  page: number
}

const KEY = ['job-post'] as const

/** The last page MEN-2 received, which stays on screen when searching too fast (UX-DR27). */
const LAST_KEY = [...KEY, 'last'] as const

/**
 * MEN-2: 20 listed Jobs a page, newest first or by relevance; the previous page stays while the next loads. A
 * refusal (a 4xx, such as `RATE_LIMITED`) is not retried.
 */
export function useJobPosts(filter: JobPostFilter) {
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: [...KEY, 'list', filter],
    queryFn: async () => {
      const page = await unwrap(
        apiClient.GET('/api/v1/mentor/job-posts', {
          params: {
            query: {
              q: filter.q,
              phase: filter.phase ? [filter.phase] : undefined,
              type: filter.type ? [filter.type] : undefined,
              skill: filter.skills.length > 0 ? filter.skills : undefined,
              category: filter.categories.length > 0 ? filter.categories : undefined,
              budget: filter.budget ? [filter.budget] : undefined,
              within: filter.within,
              page: filter.page,
            },
          },
        }),
      )
      queryClient.setQueryData(LAST_KEY, page)
      return page
    },
    placeholderData: keepPreviousData,
    retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 3,
  })
}

/** The last page MEN-2 received, read from the cache only. */
export function useLastJobPosts() {
  return useQuery<components['schemas']['PageResponseJobPostSummaryView']>({ queryKey: LAST_KEY, queryFn: skipToken })
}

/**
 * The Customer Rating summaries of a page of MEN-2 rows in one call (SG-43), keyed by the Job: the Customer is never
 * named.
 */
export function useCustomerRatings(postIds: string[]) {
  return useQuery({
    queryKey: [...KEY, 'customer-ratings', postIds],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/mentor/customer-ratings', { params: { query: { post: postIds } } })),
    enabled: postIds.length > 0,
    retry: false,
  })
}

/** MEN-3: one listed Job. */
export function useJobPost(postId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'detail', postId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/mentor/job-posts/{postId}', { params: { path: { postId: postId ?? '' } } })),
    enabled: postId !== undefined,
    retry: false,
  })
}

/** MEN-3's Customer Rating summary (AD-33). */
export function useCustomerRating(postId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'customer-rating', postId],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/mentor/job-posts/{postId}/customer-rating', {
          params: { path: { postId: postId ?? '' } },
        }),
      ),
    enabled: postId !== undefined,
    retry: false,
  })
}

/** Whether the user can bid now (FR-13): MEN-3's Job-limit banner and the bid form's gate. */
export function useMentorCapacity() {
  return useQuery({ queryKey: [...KEY, 'capacity'], queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/capacity')) })
}
