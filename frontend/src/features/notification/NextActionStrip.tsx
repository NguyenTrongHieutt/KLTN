import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { DeadlineChip } from './DeadlineChip'
import { useTodos } from './hooks'
import { todoText } from './texts'

/**
 * The next-action strip (FR-28, UX-DR14): the single most urgent open to-do, its deadline chip, a
 * button to the exact surface, and "và N việc khác" to the to-do list. It is hidden when nothing is
 * open (never filler), while loading, and while the poll fails; it cannot be dismissed.
 * @param compact the 36 px button of Mentor and Student screens; Customers get 44 px
 */
export function NextActionStrip({ compact }: { compact: boolean }) {
  const todos = useTodos()
  const first = todos.data?.items[0]
  if (!first || todos.isError) {
    return null
  }
  const others = (todos.data?.total ?? 1) - 1
  return (
    <section
      aria-label={t('screen.shared.todo.stripLabel')}
      className="border-b border-primary-border border-l-4 border-l-primary bg-primary-soft"
    >
      <div className="mx-auto flex min-h-strip-min-height max-w-content-max flex-wrap items-center gap-x-4 gap-y-2 px-gutter-mobile py-2 md:px-gutter-desktop">
        <p className="flex min-w-0 grow flex-wrap items-center gap-x-3 gap-y-1">
          <span className="text-label">{todoText(first)}</span>
          {first.deadlineAt && <DeadlineChip deadlineAt={first.deadlineAt} near={first.deadlineNear} />}
          {others > 0 && (
            <Link to="/viec-can-lam" className="text-body-sm text-primary underline-offset-4 hover:underline">
              {t('screen.shared.todo.more', { n: others })}
            </Link>
          )}
        </p>
        <Link
          to={first.link}
          className={cn(buttonVariants({ size: compact ? 'compact' : 'default' }), 'w-full md:w-auto')}
        >
          {t('screen.shared.todo.open')}
        </Link>
      </div>
    </section>
  )
}
