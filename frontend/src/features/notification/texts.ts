import { roleLabel, type MyJob } from '@/features/job/jobRoleApi'
import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import type { NotificationItem, TodoItem } from './api'

/** A to-do's strip and list sentence: `todo.{key}` with its parameters (EXPERIENCE.md, To-do Items). */
export function todoText(todo: Pick<TodoItem, 'key' | 'params'>): string {
  return t(`todo.${todo.key}` as MessageKey, todo.params)
}

/**
 * A to-do's context line (UX-DR88, AD-27): "{job} · Bạn là {role}" for a Job's to-do that carries the recipient's
 * role, "{job}" for one that does not, and "Tài khoản" for the account's own. The Job's title comes from "Công
 * việc" (`/my-jobs`), never from a join across modules; a Job not listed there falls back to the to-do's own
 * `job` parameter, and with neither only the role is said.
 */
export function todoContextText(todo: Pick<TodoItem, 'jobId' | 'role' | 'params'>, jobs: MyJob[] | undefined): string {
  if (!todo.jobId) {
    return t('screen.shared.todo.account')
  }
  const job = jobs?.find((found) => found.jobId === todo.jobId)?.title ?? todo.params.job
  if (!todo.role) {
    return job ?? ''
  }
  const role = roleLabel(todo.role)
  return job ? t('screen.shared.todo.context', { job, role }) : t('screen.shared.todo.contextRole', { role })
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
