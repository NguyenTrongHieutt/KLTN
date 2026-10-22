import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { busyKey } from '@/features/job/jobBusy'
import { apiClient, unwrap } from '@/lib/apiClient'

export type FinalMerge = components['schemas']['FinalMergeView']

/** Where "Đăng công việc ghép mã" opens CUS-6, pre-filled to merge a Job's code (FR-38). */
export const MERGE_CODE_PARAM = 'ghep-ma'

/** How often the step reads again while the code is checked or merged. */
const RUNNING_POLL_MS = 5_000

/**
 * The Customer's step "Cho phép hợp nhất" (FR-37, AD-10); others, and an Assessment Job, find nothing. It
 * reads again while the Final Merge is checked or merged.
 */
export function useFinalMerge(jobId: string) {
  return useQuery({
    queryKey: ['final-merge', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/final-merge', { params: { path: { jobId } } })),
    retry: false,
    refetchInterval: (query) => {
      const status = query.state.data?.merge?.status
      const zip = query.state.data?.zip?.status
      return status === 'CHECKING' || status === 'CONFIRMED' || zip === 'BUILDING' ||
        (query.state.data?.status === 'ENDED' && !zip)
        ? RUNNING_POLL_MS
        : false
    },
  })
}

/** A fresh Merge App install link with its single-use state; the page then goes to GitHub. */
export function useInstallLink(jobId: string) {
  return useMutation({
    mutationFn: () =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/final-merge/install-link', { params: { path: { jobId } } })),
  })
}

/** "Hợp nhất vào dự án" (N-18): the merge starts, so the step and the busy banner refresh. */
export function useConfirmFinalMerge(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/final-merge/confirm', {
          params: { path: { jobId } },
          body: { agreed: true },
        }),
      ),
    onSuccess: async (view) => {
      queryClient.setQueryData(['final-merge', jobId], view)
      await queryClient.invalidateQueries({ queryKey: busyKey(jobId) })
    },
  })
}

/** "Nhận mã dạng ZIP" (FR-38): the authorization ends and the ZIP Fallback follows. */
export function useDeclineFinalMerge(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/final-merge/decline', { params: { path: { jobId } } })),
    onSuccess: (view) => queryClient.setQueryData(['final-merge', jobId], view),
  })
}
