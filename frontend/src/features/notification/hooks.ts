import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { POLLED, POLLING, pollEvery } from '@/config/polling'
import {
  fetchNotifications,
  fetchTodos,
  fetchUnreadCount,
  markAllRead,
  markRead,
  NOTIFICATION_QUERY_KEY,
  PAGE_SIZE,
} from './api'

/** The caller's open to-dos in order: the strip, the to-do list, and the tab-bar count (FR-28). */
export function useTodos(jobId?: string) {
  return useQuery({
    queryKey: [...NOTIFICATION_QUERY_KEY, 'todos', jobId ?? 'all'],
    queryFn: () => fetchTodos(jobId),
    refetchInterval: pollEvery(POLLING.todos),
    retry: false,
    meta: POLLED,
  })
}

/** The bell's and the tab bar's unread count (UX-DR11). */
export function useUnreadCount(enabled = true) {
  return useQuery({
    queryKey: [...NOTIFICATION_QUERY_KEY, 'unread'],
    queryFn: fetchUnreadCount,
    refetchInterval: pollEvery(POLLING.unreadCount),
    retry: false,
    meta: POLLED,
    enabled,
  })
}

/** ALL-1: the notifications of the last 90 days, newest first, a page at a time. */
export function useNotifications() {
  return useInfiniteQuery({
    queryKey: [...NOTIFICATION_QUERY_KEY, 'list'],
    queryFn: ({ pageParam }) => fetchNotifications(pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.total ? last.page + 1 : undefined),
  })
}

/** The bell popover: the 10 latest (UX-DR11). */
export function useLatestNotifications(enabled: boolean) {
  return useQuery({
    queryKey: [...NOTIFICATION_QUERY_KEY, 'latest'],
    queryFn: () => fetchNotifications(0, 10),
    enabled,
  })
}

export function useMarkRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: markRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
  })
}

export function useMarkAllRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: markAllRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
  })
}

export { PAGE_SIZE }
