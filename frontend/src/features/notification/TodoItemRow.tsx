import { ChevronRight, Clock, ListChecks } from 'lucide-react'
import { Link } from 'react-router'
import { cardInteractive, cardSurface } from '@/components/ui/card-surface'
import { cn } from '@/lib/utils'
import type { TodoItem } from './api'
import { DeadlineChip } from './DeadlineChip'
import { TodoContext } from './TodoContext'
import { showsDeadlineChip, todoText } from './texts'

/**
 * A to-do list item (DESIGN.md, UX-DR88): the context line ("{job} · Bạn là {role}", "{job}", or "Tài khoản"),
 * the action sentence, the deadline chip, and a chevron, behind an icon in a circle (a clock when the to-do has a
 * deadline); a deadline that decides automatically carries a warning left bar and a warning circle. Users open it; only its owning module closes it.
 */
export function TodoItemRow({ todo }: { todo: TodoItem }) {
  return (
    <Link
      to={todo.link}
      className={cn(
        'flex min-h-tap-min items-center gap-3 p-4 text-inherit no-underline md:px-5',
        cardSurface,
        cardInteractive,
        todo.tier === 1 && 'border-l-4 border-l-warning dark:border-l-warning',
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'flex size-10 shrink-0 items-center justify-center rounded-full',
          todo.tier === 1 ? 'bg-warning-soft text-warning' : 'bg-primary-soft text-primary',
        )}
      >
        {todo.deadlineAt ? <Clock className="size-5" /> : <ListChecks className="size-5" />}
      </span>
      <span className="flex min-w-0 grow flex-col gap-1">
        <TodoContext todo={todo} />
        <span className="text-label">{todoText(todo)}</span>
        {todo.deadlineAt && showsDeadlineChip(todo) && (
          <span>
            <DeadlineChip deadlineAt={todo.deadlineAt} near={todo.deadlineNear} />
          </span>
        )}
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
    </Link>
  )
}
