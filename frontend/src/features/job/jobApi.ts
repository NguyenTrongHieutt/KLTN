import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'

export type Job = components['schemas']['JobView']
export type JobSummary = components['schemas']['JobSummaryView']
export type JobDraft = components['schemas']['JobDraftRequest']
export type JobType = JobDraft['jobTypes'][number]
export type Budget = NonNullable<JobDraft['budget']>
export type JobState = Job['state']
export type SuggestedSkill = components['schemas']['SuggestedSkillView']

export const JOBS_QUERY_KEY = ['job'] as const

/** The eight Job Types in the frame's order (FR-9). */
export const JOB_TYPES: JobType[] = [
  'ASSESSMENT',
  'BUG_FIX',
  'NEW_FEATURE',
  'MODIFICATION',
  'IMPROVEMENT',
  'SECURITY_TESTING',
  'DEPLOYMENT_OPERATIONS',
  'DOCUMENTATION',
]

export const BUDGETS: Budget[] = ['UNDER_2M', 'FROM_2M_TO_5M', 'FROM_5M_TO_10M', 'OVER_10M']

/** CUS-7: the Customer's Jobs. */
export function useJobs() {
  return useQuery({ queryKey: [...JOBS_QUERY_KEY, 'list'], queryFn: () => unwrap(apiClient.GET('/api/v1/jobs')) })
}

/** CUS-6 on a saved Job. */
export function useJob(jobId: string | undefined) {
  return useQuery({
    queryKey: [...JOBS_QUERY_KEY, 'detail', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/** CUS-6's Skills suggested from the repository's languages and the Product's AI tools. */
export function useSkillSuggestions(productId: string | undefined) {
  return useQuery({
    queryKey: ['product', 'skill-suggestions', productId],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/products/{productId}/skill-suggestions', {
          params: { path: { productId: productId ?? '' } },
        }),
      ),
    enabled: productId !== undefined,
    retry: false,
    staleTime: Infinity,
  })
}

/** CUS-6 "Lưu nháp": creates the Draft, or saves it. */
export function useSaveJob(jobId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: JobDraft): Promise<string> => {
      if (jobId === undefined) {
        return (await unwrap(apiClient.POST('/api/v1/jobs', { body }))).id
      }
      await unwrap(apiClient.PUT('/api/v1/jobs/{jobId}', { params: { path: { jobId } }, body }))
      return jobId
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: JOBS_QUERY_KEY }),
  })
}

/** CUS-6 screenshots: an image or a PDF, sent as one multipart file. */
export function useAddScreenshot(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/screenshots', { params: { path: { jobId } }, body: formBody(form) }),
      )
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...JOBS_QUERY_KEY, 'detail', jobId] }),
  })
}

export function useRemoveScreenshot(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (screenshotId: string) =>
      unwrap(
        apiClient.DELETE('/api/v1/jobs/{jobId}/screenshots/{screenshotId}', {
          params: { path: { jobId, screenshotId } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...JOBS_QUERY_KEY, 'detail', jobId] }),
  })
}
