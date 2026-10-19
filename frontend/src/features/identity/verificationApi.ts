import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'

export type Verification = components['schemas']['VerificationView']

export const VERIFICATION_QUERY_KEY = ['identity', 'verification'] as const

/** A Student's own verification and the code waiting for its entry (STU-5, STU-1). */
export function useVerification(enabled = true) {
  return useQuery({
    queryKey: VERIFICATION_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/verification')),
    enabled,
  })
}

/** "Gửi mã" and "Gửi mã mới": a 6-digit code to a school address on the allowlist (FR-5). */
export function useSendSchoolEmailCode() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (email: string) => unwrap(apiClient.POST('/api/v1/verification/school-email', { body: { email } })),
    onSuccess: (view) => queryClient.setQueryData(VERIFICATION_QUERY_KEY, view),
  })
}

/**
 * "Xác minh": a wrong entry still counts, so a refusal refreshes the view for the attempts left;
 * success closes the `identity.verify_student` to-do, so the to-dos refresh too.
 */
export function useConfirmSchoolEmailCode() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) =>
      unwrap(apiClient.POST('/api/v1/verification/school-email/confirm', { body: { code } })),
    onSuccess: (view) => {
      queryClient.setQueryData(VERIFICATION_QUERY_KEY, view)
      return queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
    onError: () => queryClient.invalidateQueries({ queryKey: VERIFICATION_QUERY_KEY }),
  })
}

/**
 * "Chọn ảnh thẻ" and "Tải lên thẻ khác": the student card, as a multipart upload (FR-5, AD-21); the
 * answer is the view with the card pending.
 */
export function useUploadStudentCard() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return unwrap(apiClient.POST('/api/v1/verification/student-card', { body: formBody(form) }))
    },
    onSuccess: (view) => queryClient.setQueryData(VERIFICATION_QUERY_KEY, view),
  })
}
