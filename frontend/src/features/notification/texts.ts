import { roleLabel, type MyJob } from '@/features/job/jobRoleApi'
import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import type { NotificationItem, TodoItem } from './api'

/**
 * A to-do's strip and list sentence: `todo.{key}` with its parameters (EXPERIENCE.md, To-do Items). A Job's to-do is
 * always read beside the Job's name -- the context line above it, or the Job's own page around the strip -- so its
 * sentence says "công việc này" where the title would stand a second time (seen 2026-11-24: one title on two lines
 * running). A to-do with no Job beside it keeps the title it was given.
 */
export function todoText(todo: Pick<TodoItem, 'key' | 'params' | 'jobId'>): string {
  const params = todo.jobId && todo.params.job ? { ...todo.params, job: t('screen.shared.todo.thisJob') } : todo.params
  return t(`todo.${todo.key}` as MessageKey, params)
}

/**
 * Whether a to-do shows its deadline chip beside its sentence. A sentence that already says the date ("… · tự nghiệm
 * thu 01/12", "… trước 01/12") is not followed by "Hạn 01/12" as well (seen 2026-11-24), unless the deadline is near:
 * then the chip's warning colour is what it adds.
 */
export function showsDeadlineChip(todo: Pick<TodoItem, 'params' | 'deadlineAt' | 'deadlineNear'>): boolean {
  return todo.deadlineAt !== undefined && (todo.params.date === undefined || todo.deadlineNear)
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
  return t(`notification.${item.type}.title` as MessageKey, worded(item))
}

/** A notification's body line, when its type has one. */
export function notificationBody(item: Pick<NotificationItem, 'type' | 'params'>): string | undefined {
  const key = `notification.${item.type}.body`
  return has(key) ? t(key as MessageKey, worded(item)) : undefined
}

/**
 * The parameters with a `cause` code in the type's own words, `notification.{type}.cause.{code}`, when it has them:
 * a lapsed Crew Application says why (R6, Story 21.4). A code without words stays as it is.
 */
function worded(item: Pick<NotificationItem, 'type' | 'params'>): Record<string, string> {
  const cause = item.params.cause
  const key = `notification.${item.type}.cause.${cause}`
  return cause !== undefined && has(key) ? { ...item.params, cause: t(key as MessageKey) } : item.params
}

function has(key: string): boolean {
  let node: unknown = vi
  for (const part of key.split('.')) {
    node = (node as Record<string, unknown> | undefined)?.[part]
  }
  return typeof node === 'string'
}
