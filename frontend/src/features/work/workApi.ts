import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Board = components['schemas']['BoardView']
export type WorkItemCard = components['schemas']['WorkItemCardView']

/** The Job states with a Board: from Active on (J7 seeds it, FR-22). */
const BOARD_STATES = ['ACTIVE', 'CLOSING', 'CLOSED']

export function hasBoard(state: string): boolean {
  return BOARD_STATES.includes(state)
}

/** The four Board statuses in their order (AD-39): Cần làm → Đang làm → Chờ duyệt → Xong. */
export const STATUSES = ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'] as const

/** A Job's Board for its Mentor and its active Crew; a Board's ID is its Job's. */
export function useBoard(jobId: string | undefined) {
  return useQuery({
    queryKey: ['board', jobId ?? ''],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/work-items', { params: { path: { boardId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}
