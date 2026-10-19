import { X } from 'lucide-react'
import { Link, Outlet } from 'react-router'
import { Toaster } from '@/components/Toaster'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { MAIN_ID } from './PublicLayout'
import { RequireSignedIn } from './RequireSignedIn'
import { useFocusHeadingOnNavigate } from './useFocusHeadingOnNavigate'

/**
 * A focused task route (UX-DR8), such as CUS-3: signed in, but without the navigation, so the
 * task is all there is; "Đóng" goes back to where the task belongs.
 */
export function FocusedLayout({ title, closeTo }: { title: string; closeTo: string }) {
  useFocusHeadingOnNavigate(MAIN_ID)
  return (
    <RequireSignedIn>
      <Toaster>
        <div className="flex min-h-dvh flex-col">
          <header className="border-b border-border bg-card">
            <div className="mx-auto flex min-h-header-height max-w-[760px] items-center gap-3 px-gutter-mobile py-2 md:px-gutter-desktop">
              <span className="text-h3 font-bold text-primary">{t('app.name')}</span>
              <span className="grow truncate text-label text-muted-foreground">{title}</span>
              <Link to={closeTo} className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'gap-1')}>
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
