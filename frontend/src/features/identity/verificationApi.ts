import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import vi from '@/i18n/vi.json'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'
import { PROFILE_QUERY_KEY } from './profileApi'

export type Verification = components['schemas']['VerificationView']

/** N-26's version as the SPA shows it; the backend refuses any other one (`STALE`). */
export const STUDENT_NOTICE_VERSION = (vi as { notice: { student: { version: string } } }).notice.student.version

export const VERIFICATION_QUERY_KEY = ['identity', 'verification'] as const

/** What "Gửi mã" sends: the school email, and on a first verification the name and major to declare. */
export interface SchoolEmailSend {
  email: string
  name?: string
  major?: string
}

/** ACC-2's student email row (Story 19.4): the status and the code waiting for its entry, never the address. */
export function useVerification(enabled = true) {
  return useQuery({
    queryKey: VERIFICATION_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/verification')),
    enabled,
  })
}

/**
 * "Gửi mã" and "Gửi mã mới" after N-26: a 6-digit code to a school address on the allowlist (FR-5). The
 * backend records N-26's version with the send; the answer names the school, never the address.
 */
export function useSendSchoolEmailCode() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ email, name, major }: SchoolEmailSend) =>
      unwrap(
        apiClient.POST('/api/v1/verification/school-email', {
          body: { noticeVersion: STUDENT_NOTICE_VERSION, email, name, major },
        }),
      ),
    onSuccess: (view) => queryClient.setQueryData(VERIFICATION_QUERY_KEY, view),
  })
}

/**
 * "Xác minh": a wrong entry still counts, so a refusal refreshes the view for the attempts left;
 * success closes the `identity.verify_student` to-do and fills ACC-1's verified zone, so both refresh too.
 */
export function useConfirmSchoolEmailCode() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) =>
      unwrap(apiClient.POST('/api/v1/verification/school-email/confirm', { body: { code } })),
    onSuccess: (view) => {
      queryClient.setQueryData(VERIFICATION_QUERY_KEY, view)
      void queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_KEY })
      return queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
    onError: () => queryClient.invalidateQueries({ queryKey: VERIFICATION_QUERY_KEY }),
  })
}

/** "Bỏ xác minh", after its Confirmation dialog: the public facts go at once, so the row and ACC-1 read again. */
export function useUnlinkStudentVerification() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.DELETE('/api/v1/verification/student')),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: VERIFICATION_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_KEY })
    },
  })
}

/**
 * "Chọn ảnh thẻ" and "Tải lên thẻ khác": the student card, as a multipart upload (FR-5, AD-21), for a
 * Student until Story 19.7; the answer is the view with the card pending.
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
