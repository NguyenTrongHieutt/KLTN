import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type MentorCrew = components['schemas']['CrewView']
export type MentorCrewMember = components['schemas']['CrewMemberView']

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
