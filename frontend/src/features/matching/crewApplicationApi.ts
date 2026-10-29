import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { ApiError, apiClient, formBody, unwrap } from '@/lib/apiClient'
import type { BidProofUpload } from './applicationApi'

export type Recruitment = components['schemas']['RecruitmentView']
export type RecruitmentRequest = components['schemas']['RecruitmentRequest']
export type RecruitmentMember = components['schemas']['RecruitmentMemberView']
export type CrewApplication = components['schemas']['CrewApplicationView']
export type ApplicantCard = components['schemas']['ApplicantCardView']
export type CrewApplyRequest = components['schemas']['CrewApplyRequest']
export type CrewApplicationStatus = CrewApplication['status']
export type CrewApplicationEnd = NonNullable<CrewApplication['endReason']>
export type Commitment = components['schemas']['CommitmentView']
export type CommitmentPanel = components['schemas']['CommitmentPanelView']
export type CommitmentSignRequest = components['schemas']['CommitmentSignRequest']

/** The days a picked person has to sign the commitment (FR-81, AD-7), as `matching` counts them. */
export const SIGN_DAYS = 3

/** The commitment text's version, which the signature records; the legal wording waits for OQ5. */
export const COMMITMENT_VERSION = '1.0'

/** Skills a recruitment seeks, or an application names, at most (the Skill picker's limit). */
export const MAX_CREW_SKILLS = 10

/** The longest note to applicants on JOB-9. */
export const MAX_RECRUIT_NOTE = 1000

/** The longest note to the Mentor on FND-2. */
export const MAX_APPLICATION_NOTE = 2000

const KEY = ['crew-application'] as const

const RECRUITMENT = ['recruitment'] as const

const COMMITMENT = ['commitment'] as const

/** JOB-9: the Mentor's recruitment setting, its limits, and the Crew list (UX-DR102). */
export function useRecruitment(jobId: string | undefined) {
  return useQuery({
    queryKey: [...RECRUITMENT, jobId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/mentor/jobs/{jobId}/recruitment', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/**
 * JOB-9 "Nhận đơn xin vào nhóm": on with its slots, Skills and note, an edit, or off, at the Crew version loaded. A save
 * may close a to-do ("Mở tuyển thành viên nhóm", or the review of applications that lapsed), so the to-dos are read
 * again; a `STALE` answer reads the recruitment again, so the form starts over at the new Crew version.
 */
export function useSetRecruitment(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: RecruitmentRequest) =>
      unwrap(apiClient.PUT('/api/v1/mentor/jobs/{jobId}/recruitment', { params: { path: { jobId } }, body })),
    onSuccess: (recruitment) => {
      queryClient.setQueryData([...RECRUITMENT, jobId], recruitment)
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: KEY }),
        queryClient.invalidateQueries({ queryKey: ['job-post'] }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
    onError: (error) => {
      if (error instanceof ApiError && error.problem.code === 'STALE') {
        return queryClient.invalidateQueries({ queryKey: [...RECRUITMENT, jobId] })
      }
    },
  })
}

/** JOB-9's Applicant cards: the pending applications oldest first, then the closed ones. */
export function useJobCrewApplications(jobId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'job', jobId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/mentor/jobs/{jobId}/crew-applications', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/** "Không chọn": no reason asked; the applicant learns only that they were not picked. */
export function useDeclineCrewApplication(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (crewApplicationId: string) =>
      unwrap(
        apiClient.POST('/api/v1/mentor/jobs/{jobId}/crew-applications/{crewApplicationId}/decline', {
          params: { path: { jobId, crewApplicationId } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/** MEN-3 and CUS-7 "Việc tôi tham gia": the user's own Crew Applications, newest first. */
export function useMyCrewApplications(enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'mine'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/crew-applications')),
    enabled,
  })
}

/** FND-2 "Gửi đơn" (FR-81): the note, the Skills, N-5, and the proof in its order. */
export function useApplyToCrew(postId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CrewApplyRequest) =>
      unwrap(apiClient.POST('/api/v1/mentor/job-posts/{postId}/crew-applications', { params: { path: { postId } }, body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/** MEN-3's "Rút" while the application is pending. */
export function useWithdrawCrewApplication() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (crewApplicationId: string) =>
      unwrap(
        apiClient.POST('/api/v1/mentor/crew-applications/{crewApplicationId}/withdraw', {
          params: { path: { crewApplicationId } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/** FND-2: the user's proof uploads waiting for their application to the Job, oldest first. */
export function useCrewProofUploads(postId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'proof-files', postId],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/mentor/job-posts/{postId}/crew-application-files', {
          params: { path: { postId: postId ?? '' } },
        }),
      ),
    enabled: postId !== undefined && enabled,
    retry: false,
  })
}

/** FND-2 "Thêm tệp": an image or a PDF, checked and kept for the application (AD-21). */
export function useAddCrewProofFile(postId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return unwrap(
        apiClient.POST('/api/v1/mentor/job-posts/{postId}/crew-application-files', {
          params: { path: { postId } },
          body: formBody(form),
        }),
      )
    },
    onSuccess: (upload) =>
      queryClient.setQueryData<BidProofUpload[]>([...KEY, 'proof-files', postId], (uploads) => [...(uploads ?? []), upload]),
  })
}

/** FND-2: removes an upload no application carries yet. */
export function useRemoveCrewProofFile(postId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (fileId: string) =>
      unwrap(
        apiClient.DELETE('/api/v1/mentor/job-posts/{postId}/crew-application-files/{fileId}', {
          params: { path: { postId, fileId } },
        }),
      ),
    onSuccess: (_, fileId) =>
      queryClient.setQueryData<BidProofUpload[]>([...KEY, 'proof-files', postId], (uploads) =>
        (uploads ?? []).filter((upload) => upload.fileId !== fileId),
      ),
  })
}

/**
 * JOB-9 "Chọn" (FR-81, Story 21.5): the pick commits only after `job` took the Job row lock and checked the Crew cap
 * and every rule; the card answers as picked. The recruitment, the applications, and the to-dos are read again, since a
 * pick may fill the last place and lapse the other applications.
 */
export function usePickCrewApplication(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (crewApplicationId: string) =>
      unwrap(
        apiClient.POST('/api/v1/mentor/jobs/{jobId}/crew-applications/{crewApplicationId}/pick', {
          params: { path: { jobId, crewApplicationId } },
        }),
      ),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: KEY }),
        queryClient.invalidateQueries({ queryKey: [...RECRUITMENT, jobId] }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ]),
  })
}

/** JOB-9 "Rút lựa chọn" (R4): a pick not yet signed ends, and its place returns while recruitment is on. */
export function useWithdrawPick(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (crewApplicationId: string) =>
      unwrap(
        apiClient.POST('/api/v1/mentor/jobs/{jobId}/crew-applications/{crewApplicationId}/withdraw-pick', {
          params: { path: { jobId, crewApplicationId } },
        }),
      ),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: KEY }),
        queryClient.invalidateQueries({ queryKey: [...RECRUITMENT, jobId] }),
      ]),
  })
}

/** MEN-3's Commitment panel (UX-DR103): the user's latest pick on the Job, with the panel while it waits. */
export function useCommitment(jobId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...COMMITMENT, jobId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/crew/jobs/{jobId}/commitment', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined && enabled,
    retry: false,
  })
}

/** A commitment command answers the pick as it now stands; a refusal reads it again, an ended pick included. */
function useCommitmentCommand<T>(jobId: string, run: (body: T) => Promise<Commitment>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: run,
    onSuccess: (commitment) => queryClient.setQueryData([...COMMITMENT, jobId], commitment),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: [...COMMITMENT, jobId] }),
        queryClient.invalidateQueries({ queryKey: KEY }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
        // The signature changes the role in the Job, so MEN-3, "Công việc" and JOB-H read it again.
        queryClient.invalidateQueries({ queryKey: JOBS_QUERY_KEY }),
      ]),
  })
}

/** "Ký và tham gia" (FR-81): the NDA and the commitment, with N-5 and, for a Code Job, N-23. */
export function useSignCommitment(jobId: string) {
  return useCommitmentCommand(jobId, (body: CommitmentSignRequest) =>
    unwrap(apiClient.POST('/api/v1/crew/jobs/{jobId}/commitment', { params: { path: { jobId } }, body })),
  )
}

/** "Từ chối" (R4): the pick ends, and the Mentor is told. */
export function useDeclineCommitment(jobId: string) {
  return useCommitmentCommand(jobId, () =>
    unwrap(apiClient.POST('/api/v1/crew/jobs/{jobId}/commitment/decline', { params: { path: { jobId } } })),
  )
}
