import { Link, useLocation } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { DeadlineChip } from './DeadlineChip'
import { useTodos } from './hooks'
import { TodoContext } from './TodoContext'
import { showsDeadlineChip, todoText } from './texts'

/**
 * The next-action strip (FR-28, UX-DR14, UX-DR88): the single most urgent open to-do across every role the user
 * holds, outside a Job with its context line ("{job} · Bạn là {role}" or "Tài khoản"), its deadline chip, a button
 * to the exact surface, and "và N việc khác" to the to-do list. Inside a Job it shows that Job's to-dos only, with
 * no context line. It is hidden when nothing is open (never filler), while loading, and while the poll fails; it
 * cannot be dismissed. It runs edge to edge under the top bar, its content on the page's left edge. On the very page
 * the to-do opens, the button would lead nowhere new, so only the sentence and its deadline stay (2026-11-24).
 * @param jobId the Job whose screen is open, if any
 */
export function NextActionStrip({ jobId }: { jobId?: string }) {
  const { pathname } = useLocation()
  const todos = useTodos(jobId)
  const first = todos.data?.items[0]
  if (!first || todos.isError) {
    return null
  }
  const others = (todos.data?.total ?? 1) - 1
  return (
    <section
      aria-label={t('screen.shared.todo.stripLabel')}
      className="border-b border-primary-border bg-primary-soft"
    >
      <div className="mx-auto flex min-h-strip-min-height max-w-content-max flex-wrap items-center gap-x-4 gap-y-2 px-gutter-mobile py-2 md:px-gutter-desktop">
        {/* The sentence wraps inside its own column, so a long one never pushes the button to a row of its own: on a
            phone the strip is on every page, and a full-width button under it took a fifth of the screen (2026-11-24). */}
        <div className="flex min-w-0 grow basis-0 flex-col gap-0.5">
          {jobId === undefined && <TodoContext todo={first} />}
          <p className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="text-label">{todoText(first)}</span>
            {first.deadlineAt && showsDeadlineChip(first) && (
              <DeadlineChip deadlineAt={first.deadlineAt} near={first.deadlineNear} />
            )}
            {others > 0 && (
              <Link to="/viec-can-lam" className="text-body-sm text-primary underline-offset-4 hover:underline">
                {t('screen.shared.todo.more', { n: others })}
              </Link>
            )}
          </p>
        </div>
        {first.link !== pathname && (
          <Link to={first.link} className={cn(buttonVariants(), 'shrink-0')}>
            {t('screen.shared.todo.open')}
          </Link>
        )}
      </div>
    </section>
  )
}
