import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router'
import { cn } from '@/lib/utils'
import type { TodoItem } from './api'
import { DeadlineChip } from './DeadlineChip'
import { todoText } from './texts'

/**
 * A to-do list item (DESIGN.md): the Job or Product name as a caption when the to-do names one, the
 * action sentence, the deadline chip, and a chevron; a deadline that decides automatically carries a
 * warning left bar. Users open it; only its owning module closes it.
 */
export function TodoItemRow({ todo }: { todo: TodoItem }) {
  const context = todo.params.context
  return (
    <Link
      to={todo.link}
      className={cn(
        'flex min-h-tap-min items-center gap-3 rounded-lg border border-border bg-card p-4 hover:bg-muted',
        todo.tier === 1 && 'border-l-4 border-l-warning',
      )}
    >
      <span className="flex min-w-0 grow flex-col gap-1">
        {context && <span className="text-caption text-muted-foreground">{context}</span>}
        <span className="text-label">{todoText(todo)}</span>
        {todo.deadlineAt && (
          <span>
            <DeadlineChip deadlineAt={todo.deadlineAt} near={todo.deadlineNear} />
          </span>
        )}
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
    </Link>
  )
}
