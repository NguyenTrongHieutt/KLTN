import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type MentorTeam = components['schemas']['MentorTeamView']
export type TeamStudent = components['schemas']['TeamStudentView']
export type TeamMember = components['schemas']['TeamMemberRow']
export type TeamStudentRequest = components['schemas']['TeamStudentRequestRow']
export type TeamStudentSearch = components['schemas']['TeamStudentSearchView']
export type TeamStudentResult = components['schemas']['TeamStudentResult']
export type TeamStudentProfile = components['schemas']['TeamStudentProfileView']
export type StudentTeam = components['schemas']['StudentTeamView']
export type TeamMentor = components['schemas']['TeamMentorView']
export type TeamMentorRequest = components['schemas']['TeamMentorRequestRow']
export type TeamMentorSearch = components['schemas']['TeamMentorSearchView']
export type TeamMentorResult = components['schemas']['TeamMentorResult']

export const TEAM_QUERY_KEY = ['team'] as const

/** A search as the URL holds it (EXPERIENCE.md, Search bar): the keyword and the Skill filter. */
export interface TeamSearch {
  q: string
  skills: string[]
}

/** MEN-6: the Mentor's Team. */
export function useMentorTeam() {
  return useQuery({
    queryKey: [...TEAM_QUERY_KEY, 'mentor'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/team')),
  })
}

/** The invite Sheet's search; runs only once a keyword or a Skill is set. */
export function useStudentSearch(search: TeamSearch, enabled: boolean) {
  return useQuery({
    queryKey: [...TEAM_QUERY_KEY, 'students', search],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/mentor/team/students', {
          params: { query: { q: search.q, skills: search.skills, page: 0, size: 20 } },
        }),
      ),
    enabled,
    placeholderData: keepPreviousData,
  })
}

/** "Mời theo tên người dùng GitHub": the one verified Student with that exact username. */
export function useStudentByLogin(login: string) {
  return useQuery({
    queryKey: [...TEAM_QUERY_KEY, 'students', 'login', login],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/team/students', { params: { query: { login } } })),
    enabled: login.length > 0,
  })
}

/** PUB-4 in the Sheet, for the Mentor. */
export function useTeamStudentProfile(studentId: string | undefined) {
  return useQuery({
    queryKey: [...TEAM_QUERY_KEY, 'student', studentId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/mentor/team/students/{studentId}', { params: { path: { studentId: studentId ?? '' } } })),
    enabled: studentId !== undefined,
  })
}

/** STU-6: the Student's Team, invitations, and join requests. */
export function useStudentTeam() {
  return useQuery({
    queryKey: [...TEAM_QUERY_KEY, 'student'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/team')),
  })
}

/** STU-6's Mentor search; runs only once a keyword or a Skill is set. */
export function useMentorSearch(search: TeamSearch, enabled: boolean) {
  return useQuery({
    queryKey: [...TEAM_QUERY_KEY, 'mentors', search],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/team/mentors', {
          params: { query: { q: search.q, skills: search.skills, page: 0, size: 20 } },
        }),
      ),
    enabled,
    placeholderData: keepPreviousData,
  })
}

/** Every Team command refreshes the Team screens and the notifications they cause. */
function useTeamCommand<T>(command: (input: T) => Promise<unknown>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: command,
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: TEAM_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ]),
  })
}

/** "Mời vào nhóm". */
export function useInviteStudent() {
  return useTeamCommand((studentId: string) =>
    unwrap(apiClient.POST('/api/v1/mentor/team/invitations', { body: { studentId } })),
  )
}

/** "Nhận vào nhóm" or "Từ chối" on a join request. */
export function useAnswerJoinRequest() {
  return useTeamCommand(({ joinRequestId, accept }: { joinRequestId: string; accept: boolean }) =>
    accept
      ? unwrap(
          apiClient.POST('/api/v1/mentor/team/join-requests/{joinRequestId}/accept', { params: { path: { joinRequestId } } }),
        )
      : unwrap(
          apiClient.POST('/api/v1/mentor/team/join-requests/{joinRequestId}/decline', { params: { path: { joinRequestId } } }),
        ),
  )
}

/** "Xóa khỏi nhóm". */
export function useRemoveMember() {
  return useTeamCommand((membershipId: string) =>
    unwrap(apiClient.DELETE('/api/v1/mentor/team/members/{membershipId}', { params: { path: { membershipId } } })),
  )
}

/** "Rời nhóm". */
export function useLeaveTeam() {
  return useTeamCommand(() => unwrap(apiClient.DELETE('/api/v1/team/membership')))
}

/** "Xin vào nhóm". */
export function useRequestToJoin() {
  return useTeamCommand((mentorId: string) => unwrap(apiClient.POST('/api/v1/team/join-requests', { body: { mentorId } })))
}

/** "Đồng ý" or "Từ chối" on an invitation. */
export function useAnswerInvitation() {
  return useTeamCommand(({ invitationId, accept }: { invitationId: string; accept: boolean }) =>
    accept
      ? unwrap(apiClient.POST('/api/v1/team/invitations/{invitationId}/accept', { params: { path: { invitationId } } }))
      : unwrap(apiClient.POST('/api/v1/team/invitations/{invitationId}/decline', { params: { path: { invitationId } } })),
  )
}
