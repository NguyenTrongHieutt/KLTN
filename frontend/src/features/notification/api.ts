import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type NotificationItem = components['schemas']['NotificationItem']
export type NotificationPage = components['schemas']['PageResponseNotificationItem']
export type TodoItem = components['schemas']['TodoItem']
export type TodoList = components['schemas']['TodoList']

/** Every query of the `notification` module; a successful command invalidates them all. */
export const NOTIFICATION_QUERY_KEY = ['notification'] as const

export const PAGE_SIZE = 20

export function fetchTodos(jobId?: string): Promise<TodoList> {
  return unwrap(apiClient.GET('/api/v1/todos', { params: { query: jobId ? { jobId } : {} } }))
}

export function fetchUnreadCount(): Promise<number> {
  return unwrap(apiClient.GET('/api/v1/notifications/unread-count')).then((answer) => answer.count)
}

export function fetchNotifications(page: number, size = PAGE_SIZE): Promise<NotificationPage> {
  return unwrap(apiClient.GET('/api/v1/notifications', { params: { query: { page, size } } }))
}

export function markRead(notificationId: string): Promise<void> {
  return unwrap(apiClient.POST('/api/v1/notifications/{notificationId}/read', { params: { path: { notificationId } } }))
}

export function markAllRead(): Promise<void> {
  return unwrap(apiClient.POST('/api/v1/notifications/read-all'))
}
