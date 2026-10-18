import { SearchX } from 'lucide-react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'

/**
 * The one screen for an unknown route and for an object the API answers `NOT_FOUND` for, which
 * also covers what the caller may not see (SG-31, UX-DR26).
 */
export function NotFoundScreen() {
  usePageTitle(t('screen.shared.notFound.title'))

  return (
    <div className="mx-auto max-w-reading-max px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <section className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-14 text-center">
        <SearchX aria-hidden="true" className="size-12 text-muted-foreground" />
        <PageHeading className="text-h2">{t('screen.shared.notFound.heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.shared.notFound.detail')}</p>
        <Link to="/" className={buttonVariants()}>
          {t('screen.shared.notFound.home')}
        </Link>
      </section>
    </div>
  )
}
