import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Board = components['schemas']['BoardView']
export type WorkItemCard = components['schemas']['WorkItemCardView']
export type WorkItemDetail = components['schemas']['WorkItemDetailView']
export type WorkItemRequest = components['schemas']['WorkItemRequest']

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
