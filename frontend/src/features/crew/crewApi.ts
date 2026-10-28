import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type CrewPanel = components['schemas']['CrewPanelView']
export type CrewRow = components['schemas']['CrewRowView']
export type CrewCandidate = components['schemas']['CrewCandidateView']
export type CrewAssignment = components['schemas']['CrewAssignmentView']
export type CrewConfirmRequest = components['schemas']['CrewConfirmRequest']

const panelKey = (jobId: string) => ['crew', 'panel', jobId] as const
const ASSIGNMENTS_KEY = ['crew', 'assignments'] as const

/** The Mentor's Crew panel on JOB-6 (FR-75). */
export function useCrewPanel(jobId: string) {
  return useQuery({
    queryKey: panelKey(jobId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/jobs/{jobId}/crew', { params: { path: { jobId } } })),
    retry: false,
  })
}

/** A Crew command answers the panel as it now stands; a refusal reloads it, a stale one included. */
function usePanelCommand<T>(jobId: string, run: (body: T) => Promise<CrewPanel>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: run,
    onSuccess: (panel) => queryClient.setQueryData(panelKey(jobId), panel),
    onError: () => queryClient.invalidateQueries({ queryKey: panelKey(jobId) }),
  })
}

/** "Thêm thành viên": a Team member joins the Crew as assigned. */
export function useAddCrewMember(jobId: string) {
  return usePanelCommand(jobId, (body: { studentId: string; crewVersion: number }) =>
    unwrap(apiClient.POST('/api/v1/mentor/jobs/{jobId}/crew', { params: { path: { jobId } }, body })),
  )
}

/** "Bỏ khỏi nhóm" before the Job starts. */
export function useRemoveCrewMember(jobId: string) {
  return usePanelCommand(jobId, ({ rowId, crewVersion }: { rowId: string; crewVersion: number }) =>
    unwrap(
      apiClient.POST('/api/v1/mentor/jobs/{jobId}/crew/{crewRowId}/remove', {
        params: { path: { jobId, crewRowId: rowId } },
        body: { crewVersion },
      }),
    ),
  )
}

/** "Lưu và gửi cho nhóm xác nhận": a new Crew Terms version for every party. */
export function useSaveCrewTerms(jobId: string) {
  return usePanelCommand(jobId, (body: { crewVersion: number; shares: { accountId: string; percent: number }[] }) =>
    unwrap(apiClient.PUT('/api/v1/mentor/jobs/{jobId}/crew/terms', { params: { path: { jobId } }, body })),
  )
}

/** "Áp dụng khi mọi người đã xác nhận": the Crew after a change on a running Job (Story 11.3). */
export function useChangeCrew(jobId: string) {
  return usePanelCommand(jobId, (body: { crewVersion: number; shares: { accountId: string; percent: number }[] }) =>
    unwrap(apiClient.PUT('/api/v1/mentor/jobs/{jobId}/crew/change', { params: { path: { jobId } }, body })),
  )
}

/** "Xác nhận lại nhóm thực hiện" after a lock owed it (R-1 H1). */
export function useReconfirmCrew(jobId: string) {
  return usePanelCommand(jobId, (crewVersion: number) =>
    unwrap(apiClient.POST('/api/v1/mentor/jobs/{jobId}/crew/reconfirm', { params: { path: { jobId } }, body: { crewVersion } })),
  )
}

/** "Việc tôi tham gia" on CUS-7: the user's Crew assignments, their own share only (AD-41). */
export function useCrewAssignments() {
  return useQuery({ queryKey: ASSIGNMENTS_KEY, queryFn: () => unwrap(apiClient.GET('/api/v1/crew/assignments')) })
}

function useAssignmentCommand<T>(run: (body: T) => Promise<unknown>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: run,
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ASSIGNMENTS_KEY }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
        // A confirmation or a decline changes the role in the Job, so "Công việc" and JOB-H read it again.
        queryClient.invalidateQueries({ queryKey: JOBS_QUERY_KEY }),
      ]),
  })
}

/** "Ký và xác nhận": the NDA signature, once per Job, and the confirmation of the version shown. */
export function useConfirmCrewTerms(jobId: string) {
  return useAssignmentCommand((body: CrewConfirmRequest) =>
    unwrap(apiClient.POST('/api/v1/crew/jobs/{jobId}/confirm', { params: { path: { jobId } }, body })),
  )
}

/** "Từ chối": the Student leaves this Crew and stays in the Mentor's Team. */
export function useDeclineCrewTerms(jobId: string) {
  return useAssignmentCommand((crewTermsVersionNo: number) =>
    unwrap(apiClient.POST('/api/v1/crew/jobs/{jobId}/decline', { params: { path: { jobId } }, body: { crewTermsVersionNo } })),
  )
}
