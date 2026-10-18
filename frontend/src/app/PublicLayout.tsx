import { Link, Outlet } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { useEnvironment } from '@/features/shared/useEnvironment'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { useFocusHeadingOnNavigate } from './useFocusHeadingOnNavigate'

export const MAIN_ID = 'noi-dung-chinh'

/**
 * The frame of every public screen (Accessibility Floor): the skip link, then the header, main,
 * and footer landmarks. Signed-in shells with role navigation arrive with sign-in.
 */
export function PublicLayout() {
  useFocusHeadingOnNavigate(MAIN_ID)
  const environment = useEnvironment()

  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href={`#${MAIN_ID}`}
        className={cn(
          buttonVariants({ variant: 'secondary', size: 'compact' }),
          'sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50',
        )}
      >
        {t('shell.skipToContent')}
      </a>
      <header className="border-b border-border bg-card">
        {/* Wraps instead of overflowing when the text is enlarged at narrow widths (WCAG 1.4.10). */}
        <div className="mx-auto flex min-h-header-height max-w-content-max flex-wrap items-center gap-x-4 gap-y-2 px-gutter-mobile py-2 md:px-gutter-desktop">
          <Link to="/" className="text-h3 font-bold text-primary" aria-label={t('shell.home')}>
            {t('app.name')}
          </Link>
          <span className="grow" />
          <Link to="/dang-nhap" className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
            {t('shell.signIn')}
          </Link>
        </div>
      </header>
      <main id={MAIN_ID} tabIndex={-1} className="grow focus:outline-none">
        <Outlet />
      </main>
      <footer className="border-t border-border bg-card">
        <div className="mx-auto flex max-w-content-max flex-wrap items-center gap-x-6 gap-y-2 px-gutter-mobile py-4 text-caption text-muted-foreground md:px-gutter-desktop">
          <nav aria-label={t('shell.footerNav')}>
            <Link to="/quyen-rieng-tu" className="text-body-sm text-primary underline-offset-4 hover:underline">
              {t('shell.privacy')}
            </Link>
          </nav>
          <span className="grow">{t('shell.copyright')}</span>
          {environment.data?.name === 'dev' && (
            <span>
              {t('environment.label')}: {t('environment.dev')}
            </span>
          )}
        </div>
      </footer>
    </div>
  )
}
