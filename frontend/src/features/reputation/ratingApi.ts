import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Ratings = components['schemas']['RatingsView']
export type Ratee = components['schemas']['RateeView']
export type GivenRating = components['schemas']['RatingView']

/** JOB-1's Ratings (FR-49) and, for a Crew member, whether they gave private feedback (FR-78). */
export function useRatings(jobId: string) {
  return useQuery({
    queryKey: ['ratings', jobId],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/boards/{boardId}/ratings', {
          params: { path: { boardId: jobId } },
        }),
      ),
    retry: false,
  })
}

/** One Rating, final once sent; refreshes the section and the to-dos. */
export function useRate(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { rateeId: string; stars: number; comment: string }) =>
      unwrap(
        apiClient.POST('/api/v1/boards/{boardId}/ratings', {
          params: { path: { boardId: jobId } },
          body,
        }),
      ),
    onSuccess: async (ratings) => {
      queryClient.setQueryData(['ratings', jobId], ratings)
      await queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
  })
}

/** Private feedback about the Mentor, which only Administrators read (FR-78). */
export function useGiveFeedback(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { stars: number; comment?: string }) =>
      unwrap(
        apiClient.POST('/api/v1/boards/{boardId}/private-feedback', {
          params: { path: { boardId: jobId } },
          body,
        }),
      ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['ratings', jobId] }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
  })
}
