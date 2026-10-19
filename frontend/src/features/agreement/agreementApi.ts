import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type AgreementDraft = components['schemas']['AgreementDraftView']
export type AgreementVersion = components['schemas']['AgreementVersionView']
export type AgreementContent = components['schemas']['Content']
export type SaveAgreementRequest = components['schemas']['SaveRequest']
export type Problem = components['schemas']['Problem']

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
