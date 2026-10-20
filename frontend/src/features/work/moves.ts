import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useToasts } from '@/components/toasts'
import { t } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'
import type { Board, WorkItemCard } from './workApi'

export type MoveStatus = 'TODO' | 'IN_PROGRESS'

export interface Move {
  to: MoveStatus
  /** Why the move is shown disabled, if it is. */
  reason?: string
}

/**
 * The moves the caller may make on a Work Item (FR-24, AD-39): between "Cần làm" and "Đang làm" for the
 * Mentor and the assignee, leaving "Cần làm" only while the Milestone is held. Submission, approval,
 * and return come with Story 5.4.
 */
export function movesFor(board: Board, item: WorkItemCard): Move[] {
  if (board.readOnly) {
    return []
  }
  const mayMove = board.mentor || item.assignee?.accountId === board.viewerId
  if (!mayMove) {
    return []
  }
  if (item.status === 'TODO') {
    const held = board.milestones.find((milestone) => milestone.milestoneId === item.milestoneId)?.held ?? false
    return [{ to: 'IN_PROGRESS', reason: held ? undefined : t('screen.board.move.notHeld') }]
  }
  if (item.status === 'IN_PROGRESS') {
    return [{ to: 'TODO' }]
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
