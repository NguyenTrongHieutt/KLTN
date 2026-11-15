import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { busyKey } from '@/features/job/jobBusy'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type FinalMerge = components['schemas']['FinalMergeView']

/** Where "Đăng công việc ghép mã" opens CUS-6, pre-filled to merge a Job's code (FR-38). */
export const MERGE_CODE_PARAM = 'ghep-ma'

/** How often the step reads again while the code is checked, or while the closure fallback is built. */
const RUNNING_POLL_MS = 5_000

/** How often it reads again while a run is on the Customer's own Actions (EXPERIENCE.md, Polling). */
const RUN_POLL_MS = 3_000

/**
 * The closure step of the Job's Customer or its Mentor (FR-37, FR-38, UX-DR116); everyone else, and an Assessment
 * Job, finds nothing. For the Customer it reads again while the Final Merge is checked, while its run is on their
 * own GitHub Actions, and while the closure fallback is built; the Mentor's read holds the choice and its outcome
 * alone, with no run and no fallback of its own to wait for, so it never polls.
 */
export function useFinalMerge(jobId: string) {
  return useQuery({
    queryKey: ['final-merge', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/final-merge', { params: { path: { jobId } } })),
    retry: false,
    refetchInterval: (query) => {
      if (query.state.data?.audience === 'MENTOR') {
        return false
      }
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

/**
 * "Tôi đã tự tải / clone từ GitHub" (FR-38, D10): the code receipt is recorded, the choice is spent, and the Job
 * ends. It cannot be undone.
 */
export function useConfirmCodeReceipt(jobId: string, version: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/code-receipt', {
          params: { path: { jobId } },
          body: { confirmed: true, version },
        }),
      ),
    // The command ends the Job, so everything that end changes is read again: the closure section with the version
    // every other command carries, the overview, the Job, the to-dos it closed, and the busy marker it released.
    onSuccess: async (view) => {
      queryClient.setQueryData(['final-merge', jobId], view)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['closure', jobId] }),
        queryClient.invalidateQueries({ queryKey: ['progress', jobId] }),
        queryClient.invalidateQueries({ queryKey: JOBS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: busyKey(jobId) }),
      ])
    },
  })
}
