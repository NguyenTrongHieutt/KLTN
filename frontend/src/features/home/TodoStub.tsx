import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { t } from '@/i18n/t'

/**
 * `/viec-can-lam`, where a signed-in account lands. The to-do list itself arrives with the
 * surfaces that create work; until then it is empty.
 */
export function TodoStub() {
  usePageTitle(t('screen.shared.todo.title'))

  return (
    <div className="mx-auto flex max-w-content-max flex-col gap-4 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.shared.todo.heading')}</PageHeading>
      <p className="rounded-lg border border-border bg-card p-6 text-muted-foreground">{t('screen.shared.todo.empty')}</p>
    </div>
  )
}
