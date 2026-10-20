import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { useToasts } from '@/components/toasts'
import { t } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'
import type { Board, WorkItemCard } from './workApi'

export type MoveStatus = 'TODO' | 'IN_PROGRESS'

/** A Board move ("Cần làm" ↔ "Đang làm"), or a step of the Review Gate that opens its dialog. */
export type Action =
  | { kind: 'move'; to: MoveStatus; reason?: string }
  | { kind: 'submit' }
  | { kind: 'approve' }
  | { kind: 'return' }

export type SubmitRequest = components['schemas']['SubmitRequest']
export type ReviewRequest = components['schemas']['ReviewRequest']

/**
 * The actions the caller may take on a Work Item (FR-24, FR-25, AD-39): between "Cần làm" and "Đang
 * làm" for the Mentor and the assignee (leaving "Cần làm" only while the Milestone is held), "Gửi duyệt"
 * for the assignee in "Đang làm", and "Duyệt" and "Trả lại" for the Mentor in "Chờ duyệt".
 */
export function actionsFor(board: Board, item: WorkItemCard): Action[] {
  if (board.readOnly) {
    return []
  }
  const assignee = item.assignee?.accountId === board.viewerId
  if (!board.mentor && !assignee) {
    return []
  }
  if (item.status === 'TODO') {
    const held = board.milestones.find((milestone) => milestone.milestoneId === item.milestoneId)?.held ?? false
    return [{ kind: 'move', to: 'IN_PROGRESS', reason: held ? undefined : t('screen.board.move.notHeld') }]
  }
  if (item.status === 'IN_PROGRESS') {
    return [{ kind: 'move', to: 'TODO' }, ...(assignee ? [{ kind: 'submit' } as const] : [])]
  }
  if (item.status === 'IN_REVIEW' && board.mentor) {
    return [{ kind: 'approve' }, { kind: 'return' }]
  }
  return []
}

/** Moves the Work Item at once on the Board and moves it back with a toast when the server refuses. */
export function useMoveWorkItem(jobId: string) {
  const queryClient = useQueryClient()
  const toasts = useToasts()
  const key = ['board', jobId]
  return useMutation({
    mutationFn: ({ item, to }: { item: WorkItemCard; to: MoveStatus }) =>
      unwrap(
        apiClient.POST('/api/v1/work-items/{workItemId}/move', {
          params: { path: { workItemId: item.id } },
          body: { to, version: item.version },
        }),
      ),
    onMutate: async ({ item, to }) => {
      await queryClient.cancelQueries({ queryKey: key })
      const before = queryClient.getQueryData<Board>(key)
      if (before) {
        queryClient.setQueryData<Board>(key, {
          ...before,
          items: before.items.map((other) => (other.id === item.id ? { ...other, status: to } : other)),
        })
      }
      return { before }
    },
    onError: (error, _variables, context) => {
      if (context?.before) {
        queryClient.setQueryData(key, context.before)
      }
      toasts.error(problemMessage(error, { module: 'work' }).text)
    },
    onSuccess: (moved) => {
      queryClient.setQueryData(['work-item', moved.item.id], moved)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: key }),
  })
}

/** "Gửi duyệt" (FR-25); the Board and the detail refresh after it. */
export function useSubmitWorkItem(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, body }: { itemId: string; body: SubmitRequest }) =>
      unwrap(apiClient.POST('/api/v1/work-items/{workItemId}/submit', { params: { path: { workItemId: itemId } }, body })),
    onSuccess: (saved) => {
      queryClient.setQueryData(['work-item', saved.item.id], saved)
      return queryClient.invalidateQueries({ queryKey: ['board', jobId] })
    },
  })
}

/** "Duyệt" or "Trả lại" (FR-25); the Board and the detail refresh after it. */
export function useReviewWorkItem(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, body }: { itemId: string; body: ReviewRequest }) =>
      unwrap(apiClient.POST('/api/v1/work-items/{workItemId}/review', { params: { path: { workItemId: itemId } }, body })),
    onSuccess: (saved) => {
      queryClient.setQueryData(['work-item', saved.item.id], saved)
      return queryClient.invalidateQueries({ queryKey: ['board', jobId] })
    },
  })
}
