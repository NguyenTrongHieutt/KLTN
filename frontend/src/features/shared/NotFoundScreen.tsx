import { SearchX } from 'lucide-react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { Card } from '@/components/ui/card'

/**
 * The one screen for an unknown route and for an object the API answers `NOT_FOUND` for, which
 * also covers what the caller may not see (SG-31, UX-DR26).
 */
export function NotFoundScreen() {
  usePageTitle(t('screen.shared.notFound.title'))

  return (
    <div className="mx-auto max-w-reading-max px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <Card as="section" padding="none" className="flex flex-col items-center gap-3 text-center px-4 py-10">
        <SearchX aria-hidden="true" className="size-12 text-muted-foreground" />
        <PageHeading className="text-h2">{t('screen.shared.notFound.heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.shared.notFound.detail')}</p>
        <Link to="/" className={buttonVariants()}>
          {t('screen.shared.notFound.home')}
        </Link>
      </Card>
    </div>
  )
}
