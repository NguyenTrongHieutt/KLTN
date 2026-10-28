import { useMyJobs } from '@/features/job/jobRoleApi'
import type { TodoItem } from './api'
import { todoContextText } from './texts'

/** The context line above a to-do's action sentence, in the strip and on CUS-1 (UX-DR88). */
export function TodoContext({ todo }: { todo: TodoItem }) {
  const jobs = useMyJobs()
  const text = todoContextText(todo, jobs.data)
  if (text === '') {
    return null
  }
  return <span className="block text-caption text-muted-foreground break-words">{text}</span>
}
