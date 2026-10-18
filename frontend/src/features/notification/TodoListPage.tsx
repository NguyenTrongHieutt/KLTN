import { ListChecks } from 'lucide-react'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { t } from '@/i18n/t'
import { useTodos } from './hooks'
import { TodoItemRow } from './TodoItemRow'

/**
 * `/viec-can-lam`: CUS-1, MEN-1 and STU-1's to-do list (FR-28, UX-DR14), where a signed-in account
 * lands. Open to-dos in the strip's order; the Empty state when none is open.
 */
export function TodoListPage() {
  usePageTitle(t('screen.shared.todo.title'))
  const todos = useTodos()

  return (
    <div className="mx-auto flex max-w-content-max flex-col gap-4 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.shared.todo.heading')}</PageHeading>
      <RegionState
        query={todos}
        skeleton={
          <div className="flex flex-col gap-3">
            {[0, 1, 2].map((row) => (
              <Skeleton key={row} className="h-20 w-full" />
            ))}
          </div>
        }
      >
        {todos.data && todos.data.items.length > 0 ? (
          <ul aria-label={t('screen.shared.todo.listLabel')} className="flex flex-col gap-3">
            {todos.data.items.map((todo) => (
              <li key={todo.id}>
                <TodoItemRow todo={todo} />
              </li>
            ))}
          </ul>
        ) : (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-10 text-center">
            <ListChecks aria-hidden="true" className="size-12 text-muted-foreground" />
            <p className="text-h3">{t('screen.shared.todo.empty')}</p>
          </div>
        )}
      </RegionState>
    </div>
  )
}
