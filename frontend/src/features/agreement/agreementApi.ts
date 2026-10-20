import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { apiClient, unwrap } from '@/lib/apiClient'

export type AgreementDraft = components['schemas']['AgreementDraftView']
export type AgreementVersion = components['schemas']['AgreementVersionView']
export type AgreementContent = components['schemas']['Content']
export type SaveAgreementRequest = components['schemas']['SaveRequest']
export type Problem = components['schemas']['Problem']
export type AgreementComment = components['schemas']['AgreementCommentView']
export type CommentTarget = AgreementComment['targetKind']
export type AgreementReview = components['schemas']['AgreementReviewView']
export type ConfirmedAgreement = components['schemas']['ConfirmedAgreementView']

const key = (jobId: string) => ['agreement', jobId] as const

/** The Mentor's live draft (FR-16). */
export function useMentorAgreement(jobId: string) {
  return useQuery({
    queryKey: [...key(jobId), 'draft'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/jobs/{jobId}/agreement', { params: { path: { jobId } } })),
    retry: false,
  })
}

/** "Lưu bản nháp": the whole draft at the revision the editor loaded (AD-23). */
export function useSaveAgreement(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: SaveAgreementRequest) =>
      unwrap(apiClient.PUT('/api/v1/mentor/jobs/{jobId}/agreement', { params: { path: { jobId } }, body })),
    onSuccess: (draft) => queryClient.setQueryData([...key(jobId), 'draft'], draft),
  })
}

/** "Gửi cho khách hàng" (FR-16). */
export function useShareAgreement(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (revision: number) =>
      unwrap(apiClient.POST('/api/v1/mentor/jobs/{jobId}/agreement/share', { params: { path: { jobId } }, body: { revision } })),
    onSuccess: (draft) => queryClient.setQueryData([...key(jobId), 'draft'], draft),
  })
}

/** The Customer's shared version, the latest when `version` is not given; a 404 until the first share. */
export function useCustomerAgreement(jobId: string, version: number | undefined) {
  return useQuery({
    queryKey: [...key(jobId), 'customer', version],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/jobs/{jobId}/agreement', { params: { path: { jobId }, query: { version } } })),
    retry: false,
  })
}

/** A Customer decision changes the Job's version, so the Job and every Agreement view reload. */
function useCustomerDecision<T>(jobId: string, run: (body: T) => Promise<unknown>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: run,
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: key(jobId) }),
        queryClient.invalidateQueries({ queryKey: [...JOBS_QUERY_KEY, 'detail', jobId] }),
      ]),
  })
}

/** "Góp ý" on the scope or one entity of the latest shared version (FR-16). */
export function useCommentOnAgreement(jobId: string) {
  return useCustomerDecision(jobId, (body: { versionNo: number; targetKind: CommentTarget; targetId?: string; body: string }) =>
    unwrap(apiClient.POST('/api/v1/jobs/{jobId}/agreement/comments', { params: { path: { jobId } }, body })),
  )
}

/** "Yêu cầu sửa", with the new Milestone order when the Customer reordered (FR-16). */
export function useRequestAgreementChanges(jobId: string) {
  return useCustomerDecision(jobId, (body: { version: number; versionNo: number; note: string; milestoneOrder?: string[] }) =>
    unwrap(apiClient.POST('/api/v1/jobs/{jobId}/agreement/changes', { params: { path: { jobId } }, body })),
  )
}

/** "Duyệt thỏa thuận" with N-6 and the live-system choices (FR-17). */
export function useApproveAgreement(jobId: string) {
  return useCustomerDecision(
    jobId,
    (body: { version: number; versionNo: number; freezeCommitment: boolean; liveAccessIds: string[] }) =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/agreement/approve', { params: { path: { jobId } }, body })),
  )
}

/** "Xác nhận thỏa thuận" at the Job and Crew versions the screen loaded (FR-17). */
export function useConfirmAgreement(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { version: number; crewVersion: number }) =>
      unwrap(apiClient.POST('/api/v1/mentor/jobs/{jobId}/agreement/confirm', { params: { path: { jobId } }, body })),
    onSuccess: (draft) => queryClient.setQueryData([...key(jobId), 'draft'], draft),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['mentor-job'] }),
        queryClient.invalidateQueries({ queryKey: ['crew', 'panel', jobId] }),
        queryClient.invalidateQueries({ queryKey: [...key(jobId), 'draft'] }),
      ]),
  })
}
