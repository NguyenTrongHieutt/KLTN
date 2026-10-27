import { useEffect } from 'react'
import { Link, NavLink, Outlet } from 'react-router'
import { Toaster } from '@/components/Toaster'
import { buttonVariants } from '@/components/ui/button-variants'
import { landingPath } from '@/features/identity/api'
import { SessionExpiredDialog } from '@/features/identity/SessionExpiredDialog'
import { AccountMenu } from '@/features/identity/AccountMenu'
import { useSession } from '@/features/identity/useSession'
import { useTodos, useUnreadCount } from '@/features/notification/hooks'
import { NextActionStrip } from '@/features/notification/NextActionStrip'
import { NotificationBell } from '@/features/notification/NotificationBell'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { applyTheme, DEFAULT_THEME } from '@/theme/accountTheme'
import { MAIN_ID } from './PublicLayout'
import { roleNavigation } from './roleNavigation'
import { RequireSignedIn } from './RequireSignedIn'
import { useFocusHeadingOnNavigate } from './useFocusHeadingOnNavigate'
import { usePollHealth } from './usePollHealth'

/**
 * The shell of a signed-in Customer, Mentor, or Student (UX-DR10 to UX-DR14): the role's
 * destinations as top-bar links from 768 px and as a bottom tab bar with counts on phones, the bell,
 * the avatar menu, the next-action strip, the offline line, and the toasts.
 */
export function AppLayout() {
  return (
    <RequireSignedIn>
      <SignedInShell />
    </RequireSignedIn>
  )
}

function SignedInShell() {
  useFocusHeadingOnNavigate(MAIN_ID)
  const session = useSession()
  const current = session.data
  const theme = current?.account?.theme
  const todos = useTodos()
  const unread = useUnreadCount()
  const { offline } = usePollHealth()
  // The account's theme while signed in; light again after signing out (UX-DR5).
  useEffect(() => {
    if (!theme) {
      return undefined
    }
    applyTheme(theme)
    return () => applyTheme(DEFAULT_THEME)
  }, [theme])
  if (!current) {
    return null
  }
  const destinations = roleNavigation(current)
  const counts: Record<string, number> = {
    '/viec-can-lam': todos.data?.total ?? 0,
    '/thong-bao': unread.data ?? 0,
  }

  return (
    <Toaster>
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
        <div className="mx-auto flex min-h-header-height max-w-content-max flex-wrap items-center gap-x-6 gap-y-2 px-gutter-mobile py-2 md:px-gutter-desktop">
          <Link to={landingPath(current)} className="text-h3 font-bold text-primary" aria-label={t('shell.home')}>
            {t('app.name')}
          </Link>
          <nav aria-label={t('shell.mainNav')} className="hidden md:block">
            <ul className="flex gap-6">
              {destinations.map((destination) => (
                <li key={destination.to}>
                  <NavLink
                    to={destination.to}
                    className={({ isActive }) =>
                      cn(
                        'inline-flex min-h-tap-min items-center border-b-2 text-label',
                        isActive ? 'border-primary text-primary' : 'border-transparent text-foreground hover:text-primary',
                      )
                    }
                  >
                    {t(destination.label)}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
          <span className="grow" />
          <NotificationBell offline={offline} />
          <AccountMenu session={current} offline={offline} />
        </div>
      </header>
      {offline && (
        <p role="status" className="border-b border-border bg-muted px-gutter-mobile py-1 text-center text-caption text-muted-foreground">
          {t('state.offline')}
        </p>
      )}
      <NextActionStrip compact={current.account?.role !== 'CUSTOMER'} />
      <main id={MAIN_ID} tabIndex={-1} className="grow pb-tab-bar-height focus:outline-none md:pb-0">
        <Outlet />
      </main>
      <nav
        aria-label={t('shell.tabNav')}
        className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-card pb-[env(safe-area-inset-bottom)] md:hidden"
      >
        <ul className="flex">
          {destinations.map((destination) => {
            const count = counts[destination.to] ?? 0
            return (
              <li key={destination.to} className="flex-1">
                <NavLink
                  to={destination.to}
                  className={({ isActive }) =>
                    cn(
                      'flex min-h-tab-bar-height flex-col items-center justify-center gap-0.5 text-caption',
                      isActive ? 'text-primary' : 'text-muted-foreground',
                    )
                  }
                >
                  <span className="relative">
                    <destination.icon aria-hidden="true" className="size-6" />
                    {count > 0 && (
                      <span className="absolute -top-1 -right-2 flex min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-tag text-destructive-foreground">
                        {count > 99 ? '99+' : count}
                      </span>
                    )}
                  </span>
                  {t(destination.label)}
                </NavLink>
              </li>
            )
          })}
        </ul>
      </nav>
      <SessionExpiredDialog />
    </div>
    </Toaster>
  )
}

