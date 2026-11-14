import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { busyKey } from '@/features/job/jobBusy'
import { apiClient, unwrap } from '@/lib/apiClient'

export type FinalMerge = components['schemas']['FinalMergeView']

/** Where "Đăng công việc ghép mã" opens CUS-6, pre-filled to merge a Job's code (FR-38). */
export const MERGE_CODE_PARAM = 'ghep-ma'

/** How often the step reads again while the code is checked, or while the closure fallback is built. */
const RUNNING_POLL_MS = 5_000

/** How often it reads again while a run is on the Customer's own Actions (EXPERIENCE.md, Polling). */
const RUN_POLL_MS = 3_000

/**
 * The Customer's closure step (FR-37, FR-38, UX-DR116); others, and an Assessment Job, find nothing. It reads
 * again while the Final Merge is checked, while its run is on the Customer's own GitHub Actions, and while the
 * closure fallback is built.
 */
export function useFinalMerge(jobId: string) {
  return useQuery({
    queryKey: ['final-merge', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/final-merge', { params: { path: { jobId } } })),
    retry: false,
    refetchInterval: (query) => {
      const status = query.state.data?.merge?.status
      const zip = query.state.data?.zip?.status
      if (status === 'CONFIRMED' || status === 'RUNNING') {
        return RUN_POLL_MS
      }
      return status === 'CHECKING' || zip === 'BUILDING' || (query.state.data?.status === 'ENDED' && !zip)
        ? RUNNING_POLL_MS
        : false
    },
  })
}

/** "Chép mã vào dự án của tôi" (N-18): the run starts, so the step and the busy banner refresh. */
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

/** The closure fallback instead of the Final Merge (FR-38): the row ends and the fallback follows. */
export function useDeclineFinalMerge(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/final-merge/decline', { params: { path: { jobId } } })),
    onSuccess: (view) => queryClient.setQueryData(['final-merge', jobId], view),
  })
}
