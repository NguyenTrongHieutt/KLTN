import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type EvidenceAgreementVersion = components['schemas']['EvidenceAgreementVersion']
export type EvidenceReport = components['schemas']['EvidenceReport']
export type EvidenceDecision = components['schemas']['EvidenceDecision']
export type EvidencePayments = components['schemas']['EvidencePayments']
export type EvidenceDriftWarning = components['schemas']['EvidenceDriftWarning']

const EVIDENCE_QUERY_KEY = ['admin', 'dispute-evidence'] as const

/**
 * The Evidence Package's tabs `dispute` assembles (FR-53, AD-31): each fetch is one audited view, so a tab is
 * read only when it opens, and never retried behind the Administrator's back.
 */
function evidenceQuery<T>(disputeId: string, tab: string, read: () => Promise<T>) {
  return { queryKey: [...EVIDENCE_QUERY_KEY, disputeId, tab], queryFn: read, retry: false, staleTime: Infinity }
}

/** "Phiên bản thỏa thuận". */
export function useAgreementEvidence(disputeId: string) {
  const path = { params: { path: { disputeId } } }
  return useQuery(
    evidenceQuery(disputeId, 'agreement', () => unwrap(apiClient.GET('/api/v1/admin/disputes/{disputeId}/evidence/agreement', path))),
  )
}

/** "Tiêu chí trong báo cáo bàn giao". */
export function useCriteriaEvidence(disputeId: string) {
  const path = { params: { path: { disputeId } } }
  return useQuery(
    evidenceQuery(disputeId, 'criteria', () => unwrap(apiClient.GET('/api/v1/admin/disputes/{disputeId}/evidence/criteria', path))),
  )
}

/** "Duyệt và yêu cầu sửa". */
export function useDecisionsEvidence(disputeId: string) {
  const path = { params: { path: { disputeId } } }
  return useQuery(
    evidenceQuery(disputeId, 'decisions', () => unwrap(apiClient.GET('/api/v1/admin/disputes/{disputeId}/evidence/decisions', path))),
  )
}

/** "Thanh toán": totals and refunds, never per-party parts. */
export function usePaymentsEvidence(disputeId: string) {
  const path = { params: { path: { disputeId } } }
  return useQuery(
    evidenceQuery(disputeId, 'payments', () => unwrap(apiClient.GET('/api/v1/admin/disputes/{disputeId}/evidence/payments', path))),
  )
}

/** "Cảnh báo thay đổi". */
export function useDriftEvidence(disputeId: string) {
  const path = { params: { path: { disputeId } } }
  return useQuery(
    evidenceQuery(disputeId, 'drift', () => unwrap(apiClient.GET('/api/v1/admin/disputes/{disputeId}/evidence/drift', path))),
  )
}

/** "Nhật ký kiểm toán": the Job's Audit Log, 50 entries a page. */
export function useEvidenceAudit(disputeId: string, page: number) {
  return useQuery({
    queryKey: [...EVIDENCE_QUERY_KEY, disputeId, 'audit', page],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/disputes/{disputeId}/evidence/audit', {
          params: { path: { disputeId }, query: { page } },
        }),
      ),
    retry: false,
    staleTime: Infinity,
  })
}

/** "Trò chuyện": the Job Chat, which `chat` serves under the assignee's grant and audits. */
export function useJobChatEvidence(jobId: string) {
  return useQuery({
    queryKey: [...EVIDENCE_QUERY_KEY, 'job-chat', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/chat/messages', { params: { path: { jobId } } })),
    retry: false,
    staleTime: Infinity,
  })
}

/** "Công việc": the Work Items, which `work` serves under the grant. */
export function useBoardEvidence(jobId: string) {
  return useQuery({
    queryKey: [...EVIDENCE_QUERY_KEY, 'work-items', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/work-items', { params: { path: { boardId: jobId } } })),
    retry: false,
    staleTime: Infinity,
  })
}

/** One Work Item with its reviews, notes, Attachments, and history. */
export function useWorkItemEvidence(workItemId: string) {
  return useQuery({
    queryKey: [...EVIDENCE_QUERY_KEY, 'work-item', workItemId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/work-items/{workItemId}', { params: { path: { workItemId } } })),
    retry: false,
    staleTime: Infinity,
  })
}

/** "Trang": the Documentation Space's Pages. */
export function usePagesEvidence(jobId: string) {
  return useQuery({
    queryKey: [...EVIDENCE_QUERY_KEY, 'pages', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/pages', { params: { path: { boardId: jobId } } })),
    retry: false,
    staleTime: Infinity,
  })
}

/** One Page's versions, the latest first. */
export function usePageVersionsEvidence(pageId: string) {
  return useQuery({
    queryKey: [...EVIDENCE_QUERY_KEY, 'page-versions', pageId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/pages/{pageId}/versions', { params: { path: { pageId } } })),
    retry: false,
    staleTime: Infinity,
  })
}

/** One Page version's text. */
export function usePageVersionEvidence(pageId: string, versionNo: number | undefined) {
  return useQuery({
    queryKey: [...EVIDENCE_QUERY_KEY, 'page-version', pageId, versionNo ?? 0],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/pages/{pageId}/versions/{versionNo}', {
          params: { path: { pageId, versionNo: versionNo ?? 0 } },
        }),
      ),
    enabled: versionNo !== undefined,
    retry: false,
    staleTime: Infinity,
  })
}
