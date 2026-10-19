import { X } from 'lucide-react'
import { Link, Outlet, useParams, type Params } from 'react-router'
import { Toaster } from '@/components/Toaster'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { MAIN_ID } from './PublicLayout'
import { RequireSignedIn } from './RequireSignedIn'
import { useFocusHeadingOnNavigate } from './useFocusHeadingOnNavigate'

/**
 * A focused task route (UX-DR8), such as CUS-3 and CUS-5: signed in, but without the navigation, so
 * the task is all there is; "Đóng" goes back to where the task belongs, which may depend on the
 * route's parameters.
 */
export function FocusedLayout({ title, closeTo }: { title: string; closeTo: string | ((params: Params) => string) }) {
  useFocusHeadingOnNavigate(MAIN_ID)
  const params = useParams()
  return (
    <RequireSignedIn>
      <Toaster>
        <div className="flex min-h-dvh flex-col">
          <header className="border-b border-border bg-card">
            <div className="mx-auto flex min-h-header-height max-w-[760px] items-center gap-3 px-gutter-mobile py-2 md:px-gutter-desktop">
              <span className="text-h3 font-bold text-primary">{t('app.name')}</span>
              <span className="grow truncate text-label text-muted-foreground">{title}</span>
              <Link to={typeof closeTo === 'string' ? closeTo : closeTo(params)} className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'gap-1')}>
                <X aria-hidden="true" className="size-4" />
                {t('screen.product.wizard.close')}
              </Link>
            </div>
          </header>
          <main id={MAIN_ID} tabIndex={-1} className="grow focus:outline-none">
            <Outlet />
          </main>
        </div>
      </Toaster>
    </RequireSignedIn>
  )
}
