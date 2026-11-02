import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type MentorCrew = components['schemas']['CrewView']
export type MentorCrewMember = components['schemas']['CrewMemberView']
export type CrewMe = components['schemas']['CrewMeView']

/** The longest reason a member may give when leaving (FR-82). */
export const MAX_LEFT_NOTE = 500

/**
 * JOB-6's Crew list for the Job's Mentor (FR-75, AD-41, Story 21.5): the members who signed, the picks still waiting,
 * and the Crew version the Agreement confirmation carries. Picks and their withdrawal live on JOB-9.
 */
export function useMentorCrew(jobId: string) {
  return useQuery({
    queryKey: ['crew', 'mentor', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/jobs/{jobId}/crew', { params: { path: { jobId } } })),
    retry: false,
  })
}

/**
 * JOB-1 as an active or a former member reads it, before the Board exists too (FR-77, Story 21.6): anyone else, a pick
 * not yet signed included, answers `NOT_FOUND`.
 */
export function useCrewMe(jobId: string) {
  return useQuery({
    queryKey: [...JOBS_QUERY_KEY, 'crew-me', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/crew/me', { params: { path: { jobId } } })),
    retry: false,
  })
}

/** "Rời công việc" (FR-82): at once and for good; the Job's screens and the to-dos read again after it. */
export function useLeaveJob(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (reason: string) =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/crew/leave', {
          params: { path: { jobId } },
          body: { reason: reason.trim() === '' ? undefined : reason.trim() },
        }),
      ),
    // Not awaited: the reads unmount the caller (`canLeave` false), and TanStack then skips `mutate`'s callbacks.
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: JOBS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: ['progress', jobId] })
      void queryClient.invalidateQueries({ queryKey: ['board', jobId] })
      void queryClient.invalidateQueries({ queryKey: ['data-deletion', jobId] })
      void queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
  })
}
