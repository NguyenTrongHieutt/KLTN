import { Menu } from '@base-ui/react/menu'
import { Link, NavLink, Outlet, useNavigate } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { landingPath, type Session } from '@/features/identity/api'
import { SessionExpiredDialog } from '@/features/identity/SessionExpiredDialog'
import { useSession, useSignOut } from '@/features/identity/useSession'
import { useTodos, useUnreadCount } from '@/features/notification/hooks'
import { NextActionStrip } from '@/features/notification/NextActionStrip'
import { NotificationBell } from '@/features/notification/NotificationBell'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { MAIN_ID } from './PublicLayout'
import { roleNavigation } from './roleNavigation'
import { RequireSignedIn } from './RequireSignedIn'
import { useFocusHeadingOnNavigate } from './useFocusHeadingOnNavigate'
import { usePollHealth } from './usePollHealth'

/**
 * The shell of a signed-in Customer, Mentor, or Student (UX-DR10 to UX-DR14): the role's
 * destinations as top-bar links from 768 px and as a bottom tab bar with counts on phones, the bell,
 * the avatar menu with "Đăng xuất", the next-action strip, and the offline line. The avatar is
 * initials: the CSP loads no image from GitHub.
 */
export function AppLayout() {
  return (
    <RequireSignedIn>
      <SignedInShell />
    </RequireSignedIn>
  )
}

/** A Mentor who is not approved keeps MEN-5 and the account menu only (FR-74), so no bell. */
function hasBell(session: Session | undefined): boolean {
  const account = session?.account
  return account?.role !== 'MENTOR' || account.mentorApprovalStatus === 'APPROVED'
}

function SignedInShell() {
  useFocusHeadingOnNavigate(MAIN_ID)
  const session = useSession()
  const signOut = useSignOut()
  const navigate = useNavigate()
  const current = session.data
  const bell = hasBell(current)
  const todos = useTodos()
  const unread = useUnreadCount(bell)
  const { offline } = usePollHealth()
  if (!current) {
    return null
  }
  const destinations = roleNavigation(current)
  const login = current.githubLogin ?? ''
  const counts: Record<string, number> = {
    '/viec-can-lam': todos.data?.total ?? 0,
    '/thong-bao': unread.data ?? 0,
  }

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
          {bell && <NotificationBell offline={offline} />}
          <Menu.Root>
            <Menu.Trigger
              aria-label={t('shell.accountMenu', { login })}
              className="flex size-10 items-center justify-center rounded-full bg-primary-soft text-label font-bold text-primary"
            >
              {initials(login)}
            </Menu.Trigger>
            <Menu.Portal>
              <Menu.Positioner align="end" sideOffset={6} className="z-50">
                <Menu.Popup className="min-w-44 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md">
                  <Menu.Item
                    disabled={offline}
                    className="flex min-h-tap-min cursor-pointer items-center rounded-md px-3 text-body-sm data-disabled:cursor-not-allowed data-disabled:opacity-50 data-highlighted:bg-muted"
                    onClick={() =>
                      signOut.mutate(undefined, {
                        onSuccess: () => navigate('/', { replace: true }),
                      })
                    }
                  >
                    {t('shell.signOut')}
                  </Menu.Item>
                </Menu.Popup>
              </Menu.Positioner>
            </Menu.Portal>
          </Menu.Root>
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
  )
}

function initials(login: string): string {
  return login.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() || '?'
}
