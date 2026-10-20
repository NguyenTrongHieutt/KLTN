import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'

export type Board = components['schemas']['BoardView']
export type WorkItemCard = components['schemas']['WorkItemCardView']
export type WorkItemDetail = components['schemas']['WorkItemDetailView']
export type WorkItemRequest = components['schemas']['WorkItemRequest']
export type WorkAttachment = components['schemas']['WorkAttachmentView']
export type HistoryEntry = components['schemas']['HistoryEntryView']

/** What the writer chose after the FR-47 warning: check first, or store the masked text. */
export type Resolution = 'CHECK' | 'MASK'

/** One masked FR-47 match, as the warning dialog lists it. */
export interface SensitiveMatch {
  kind: string
  type: string
  masked: string
}

/** The Job states with a Board: from Active on (J7 seeds it, FR-22). */
const BOARD_STATES = ['ACTIVE', 'CLOSING', 'CLOSED']

export function hasBoard(state: string): boolean {
  return BOARD_STATES.includes(state)
}

/** The four Board statuses in their order (AD-39): Cần làm → Đang làm → Chờ duyệt → Xong. */
export const STATUSES = ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'] as const

export const TYPES = ['STORY', 'TASK', 'BUG'] as const

export const PRIORITIES = ['HIGH', 'MEDIUM', 'LOW'] as const

/** A Job's Board as the viewer may see it; a Board's ID is its Job's. */
export function useBoard(jobId: string | undefined) {
  return useQuery({
    queryKey: ['board', jobId ?? ''],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/work-items', { params: { path: { boardId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/** One Work Item with its checklist and history. */
export function useWorkItem(workItemId: string | undefined) {
  return useQuery({
    queryKey: ['work-item', workItemId ?? ''],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/work-items/{workItemId}', { params: { path: { workItemId: workItemId ?? '' } } })),
    enabled: workItemId !== undefined,
    retry: false,
  })
}

/** "Thêm việc" and edits (FR-23); either refreshes the Board. */
export function useSaveWorkItem(jobId: string, workItemId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: WorkItemRequest) =>
      workItemId
        ? unwrap(apiClient.PUT('/api/v1/work-items/{workItemId}', { params: { path: { workItemId } }, body }))
        : unwrap(apiClient.POST('/api/v1/boards/{boardId}/work-items', { params: { path: { boardId: jobId } }, body })),
    onSuccess: (saved) => {
      queryClient.setQueryData(['work-item', saved.item.id], saved)
      return queryClient.invalidateQueries({ queryKey: ['board', jobId] })
    },
  })
}

/** Every Attachment and note command answers the Work Item detail; the Board's cards do not change. */
function useDetailMutation<T>(workItemId: string, run: (body: T) => Promise<WorkItemDetail>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: run,
    onSuccess: (detail) => queryClient.setQueryData(['work-item', workItemId], detail),
  })
}

/** "Tải tệp lên" (FR-76, NFR-3): one file; the FR-47 choice travels as a form field, never in the URL. */
export function useAttachFile(workItemId: string) {
  return useDetailMutation(workItemId, ({ file, sensitive }: { file: File; sensitive: Resolution }) => {
    const form = new FormData()
    form.append('file', file)
    form.append('sensitive', sensitive)
    return unwrap(
      apiClient.POST('/api/v1/work-items/{workItemId}/attachments', {
        params: { path: { workItemId } },
        body: formBody(form),
      }),
    )
  })
}

/** "Thêm liên kết" (FR-76): an `https` link with an optional label. */
export function useAttachLink(workItemId: string) {
  return useDetailMutation(workItemId, (body: { url: string; label: string; sensitive: Resolution }) =>
    unwrap(apiClient.POST('/api/v1/work-items/{workItemId}/links', { params: { path: { workItemId } }, body })),
  )
}

/** "Gỡ": the author removes their Attachment while the Work Item is not Done. */
export function useRemoveAttachment(workItemId: string) {
  return useDetailMutation(workItemId, (attachmentId: string) =>
    unwrap(
      apiClient.DELETE('/api/v1/work-items/{workItemId}/attachments/{attachmentId}', {
        params: { path: { workItemId, attachmentId } },
      }),
    ),
  )
}

/** A note, or with `quotes` a correction of one of the author's notes (FR-76). */
export function useAddNote(workItemId: string) {
  return useDetailMutation(workItemId, (body: { text: string; quotes?: string; sensitive: Resolution }) =>
    unwrap(apiClient.POST('/api/v1/work-items/{workItemId}/notes', { params: { path: { workItemId } }, body })),
  )
}
