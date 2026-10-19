import { keepPreviousData, skipToken, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { ApiError, apiClient, unwrap } from '@/lib/apiClient'
import type { Budget, JobType } from './jobApi'

export type JobPostSummary = components['schemas']['JobPostSummaryView']
export type JobPost = components['schemas']['JobPostView']
export type MentorCapacity = components['schemas']['CapacityView']
export type CapacityReason = MentorCapacity['reasons'][number]

/** MEN-2's keyword and filters, as the URL keeps them (FR-12). */
export interface JobPostFilter {
  /** The keyword; results then rank by relevance (AD-36). */
  q?: string
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
 * MEN-2: 20 Open Job Posts a page, newest first or by relevance; the previous page stays while the
 * next loads. A refusal (a 4xx, such as `RATE_LIMITED`) is not retried.
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

/** MEN-3: one Open Job Post. */
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

/** Whether the Mentor can apply now (FR-13): the banners of MEN-2 and MEN-3. */
export function useMentorCapacity() {
  return useQuery({ queryKey: [...KEY, 'capacity'], queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/capacity')) })
}
