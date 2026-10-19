import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type RecommendedMentor = components['schemas']['RecommendedMentorView']
export type RecommendedPost = components['schemas']['RecommendedPostView']
export type Signals = components['schemas']['SignalsView']

/** The Skill filter, which narrows Recommendations without reordering them (AD-17, AD-38). */
export interface SkillFilterValue {
  skills: string[]
  categories: string[]
}

function query(filter: SkillFilterValue) {
  return {
    skill: filter.skills.length > 0 ? filter.skills : undefined,
    category: filter.categories.length > 0 ? filter.categories : undefined,
  }
}

/**
 * CUS-8 "Đề xuất cho công việc này" (FR-14): Mentors who could apply, in AD-17's order. Kept under
 * the invitation key, so an invitation refreshes their Invite buttons.
 */
export function useMentorRecommendations(jobId: string, filter: SkillFilterValue, enabled: boolean) {
  return useQuery({
    queryKey: ['invitation', 'recommendations', jobId, filter],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/jobs/{jobId}/recommendations', { params: { path: { jobId }, query: query(filter) } }),
      ),
    enabled,
  })
}

/** MEN-2 "Đề xuất cho bạn" (FR-14): Job Posts not yet applied to, in AD-17's order. */
export function useJobPostRecommendations(filter: SkillFilterValue, enabled: boolean) {
  return useQuery({
    queryKey: ['job-post', 'recommendations', filter],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/recommendations', { params: { query: query(filter) } })),
    enabled,
  })
}
