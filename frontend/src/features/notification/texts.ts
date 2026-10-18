import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import type { NotificationItem, TodoItem } from './api'

/** A to-do's strip and list sentence: `todo.{key}` with its parameters (EXPERIENCE.md, To-do Items). */
export function todoText(todo: Pick<TodoItem, 'key' | 'params'>): string {
  return t(`todo.${todo.key}` as MessageKey, todo.params)
}

/** A notification's title: `notification.{type}.title` (EXPERIENCE.md, Notification Inventory). */
export function notificationTitle(item: Pick<NotificationItem, 'type' | 'params'>): string {
  return t(`notification.${item.type}.title` as MessageKey, item.params)
}

/** A notification's body line, when its type has one. */
export function notificationBody(item: Pick<NotificationItem, 'type' | 'params'>): string | undefined {
  const key = `notification.${item.type}.body`
  return has(key) ? t(key as MessageKey, item.params) : undefined
}

function has(key: string): boolean {
  let node: unknown = vi
  for (const part of key.split('.')) {
    node = (node as Record<string, unknown> | undefined)?.[part]
  }
  return typeof node === 'string'
}
