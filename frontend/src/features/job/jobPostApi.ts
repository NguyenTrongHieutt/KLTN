import { keepPreviousData, useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'
import type { Budget, JobType } from './jobApi'

export type JobPostSummary = components['schemas']['JobPostSummaryView']
export type JobPost = components['schemas']['JobPostView']
export type MentorCapacity = components['schemas']['CapacityView']
export type CapacityReason = MentorCapacity['reasons'][number]

/** MEN-2's filters, as the URL keeps them (FR-12). */
export interface JobPostFilter {
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

/** MEN-2: 20 Open Job Posts a page, newest first; the previous page stays while the next loads. */
export function useJobPosts(filter: JobPostFilter) {
  return useQuery({
    queryKey: [...KEY, 'list', filter],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/mentor/job-posts', {
          params: {
            query: {
              type: filter.type ? [filter.type] : undefined,
              skill: filter.skills.length > 0 ? filter.skills : undefined,
              category: filter.categories.length > 0 ? filter.categories : undefined,
              budget: filter.budget ? [filter.budget] : undefined,
              within: filter.within,
              page: filter.page,
            },
          },
        }),
      ),
    placeholderData: keepPreviousData,
  })
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
