import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'

export type Application = components['schemas']['ApplicationView']
export type JobApplication = components['schemas']['JobApplicationView']
export type ApplyRequest = components['schemas']['ApplyRequest']
export type ApplicationStatus = Application['status']
export type BidProof = components['schemas']['BidProofView']
export type BidProofRequest = components['schemas']['BidProofRequest']
export type BidProofUpload = components['schemas']['BidProofUploadView']

/** Proof items per bid, files and links together (SG-58). */
export const MAX_PROOF = 5

/** The longest caption of a proof item. */
export const MAX_CAPTION = 200

const KEY = ['application'] as const

/** FND-1: the user's proof uploads waiting for their bid on the Job, oldest first. */
export function useProofUploads(postId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'proof-files', postId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/mentor/job-posts/{postId}/proof-files', { params: { path: { postId: postId ?? '' } } })),
    enabled: postId !== undefined && enabled,
    retry: false,
  })
}

/** FND-1 "Thêm tệp": an image or a PDF, checked and kept for the bid (AD-21); the waiting list shows it at once. */
export function useAddProofFile(postId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return unwrap(
        apiClient.POST('/api/v1/mentor/job-posts/{postId}/proof-files', {
          params: { path: { postId } },
          body: formBody(form),
        }),
      )
    },
    onSuccess: (upload) =>
      queryClient.setQueryData<BidProofUpload[]>([...KEY, 'proof-files', postId], (uploads) => [...(uploads ?? []), upload]),
  })
}

/** FND-1: removes an upload no bid carries yet. */
export function useRemoveProofFile(postId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (fileId: string) =>
      unwrap(
        apiClient.DELETE('/api/v1/mentor/job-posts/{postId}/proof-files/{fileId}', {
          params: { path: { postId, fileId } },
        }),
      ),
    onSuccess: (_, fileId) =>
      queryClient.setQueryData<BidProofUpload[]>([...KEY, 'proof-files', postId], (uploads) =>
        (uploads ?? []).filter((upload) => upload.fileId !== fileId),
      ),
  })
}

/** Whether a link may be proof (AD-21, the shared link rule): `https` with a host, no spaces. */
export function isProofLink(url: string): boolean {
  const trimmed = url.trim()
  if (!/^https:\/\/\S+$/i.test(trimmed) || trimmed.length > 2000) {
    return false
  }
  try {
    const parsed = new URL(trimmed)
    return parsed.protocol === 'https:' && parsed.hostname !== '' && parsed.username === '' && parsed.password === ''
  } catch {
    return false
  }
}

/** "Việc tôi hướng dẫn" on CUS-7, and MEN-3: the user's own bids, newest first. */
export function useMyApplications(enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'mine'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/applications')),
    enabled,
  })
}

/** CUS-8: the open bids on the Customer's Job, each with its proof. */
export function useJobApplications(jobId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'job', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/applications', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/** FND-1 "Gửi chào giá" (FR-13): the quote and the proof, in its order. */
export function useApply(postId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ApplyRequest) =>
      unwrap(apiClient.POST('/api/v1/mentor/job-posts/{postId}/applications', { params: { path: { postId } }, body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/**
 * CUS-8 "Chọn" (FR-15): J3 at the Job version the screen loaded; a 409 `STALE` means the Job moved on
 * and nothing changed (AD-23).
 */
export function useSelectApplication(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ applicationId, version }: { applicationId: string; version: number }) =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/applications/{applicationId}/select', {
          params: { path: { jobId, applicationId } },
          body: { version },
        }),
      ),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: KEY }),
        queryClient.invalidateQueries({ queryKey: ['job'] }),
      ]),
  })
}

/** MEN-3's "Rút" before selection (FR-13): the Customer is told. */
export function useWithdrawApplication() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (applicationId: string) =>
      unwrap(
        apiClient.POST('/api/v1/mentor/applications/{applicationId}/withdraw', {
          params: { path: { applicationId } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/** Whether an Application still counts: submitted, on hold, or selected. */
export function isLive(application: { status: ApplicationStatus }): boolean {
  return application.status === 'SUBMITTED' || application.status === 'ON_HOLD' || application.status === 'SELECTED'
}
