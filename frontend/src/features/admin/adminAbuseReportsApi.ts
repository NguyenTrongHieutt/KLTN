import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type AbuseReportRow = components['schemas']['AbuseReportRow']
export type AbuseReport = components['schemas']['AbuseReportDetail']
export type AbuseAction = 'HIDE' | 'ACTION_TAKEN' | 'DISMISS'

const REPORTS_QUERY_KEY = ['admin', 'abuse-reports'] as const

/** ADM-6's Abuse Report queue (FR-69). */
export function useAbuseReports() {
  return useQuery({
    queryKey: REPORTS_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/abuse-reports')),
    retry: false,
  })
}

/** One report. */
export function useAbuseReport(reportId: string | undefined) {
  return useQuery({
    queryKey: [...REPORTS_QUERY_KEY, reportId ?? ''],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/admin/abuse-reports/{reportId}', { params: { path: { reportId: reportId ?? '' } } })),
    enabled: reportId !== undefined,
    retry: false,
  })
}

type Command =
  | { action: 'take' }
  | { action: 'decide'; body: { action: AbuseAction; hideReason?: string; outcome: string } }

/** "Nhận xử lý" or the decision; the queue and the report refresh. */
export function useAbuseReportCommand(reportId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (command: Command) => {
      const path = { params: { path: { reportId } } }
      return command.action === 'take'
        ? unwrap(apiClient.POST('/api/v1/admin/abuse-reports/{reportId}/take', path))
        : unwrap(apiClient.POST('/api/v1/admin/abuse-reports/{reportId}/decision', { ...path, body: command.body }))
    },
    onSuccess: (detail) => queryClient.setQueryData([...REPORTS_QUERY_KEY, reportId], detail),
    onSettled: () => queryClient.invalidateQueries({ queryKey: REPORTS_QUERY_KEY }),
  })
}

/** The reported chat message, which `chat` serves under the report's grant and audits. */
export function useReportedMessage(messageId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...REPORTS_QUERY_KEY, 'message', messageId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/chat-messages/{messageId}', { params: { path: { messageId } } })),
    enabled,
    retry: false,
    staleTime: Infinity,
  })
}

/** The reported Work Item note, which `work` serves under the report's grant and audits. */
export function useReportedNote(noteId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...REPORTS_QUERY_KEY, 'note', noteId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/work-item-notes/{workItemNoteId}', { params: { path: { workItemNoteId: noteId } } })),
    enabled,
    retry: false,
    staleTime: Infinity,
  })
}
