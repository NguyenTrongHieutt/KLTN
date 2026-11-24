import { Bell, BriefcaseBusiness, House, Menu, Package, Search } from 'lucide-react'
import { useEffect } from 'react'
import { Link, NavLink, Outlet, ScrollRestoration, useMatch } from 'react-router'
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
import { useMatchMedia } from '@/lib/useMatchMedia'
import { cn } from '@/lib/utils'
import { applyTheme, DEFAULT_THEME } from '@/theme/accountTheme'
import { TOP_SEARCH_QUERY } from '@/features/job/findWorkUrl'
import { AppSidebar, SIDEBAR_ID, type Destination } from './AppSidebar'
import { CreateMenu } from './CreateMenu'
import { MAIN_ID } from './PublicLayout'
import { RequireSignedIn } from './RequireSignedIn'
import { TopSearch } from './TopSearch'
import { useFocusHeadingOnNavigate } from './useFocusHeadingOnNavigate'
import { usePollHealth } from './usePollHealth'
import { useSidebarCollapsed } from './useSidebarCollapsed'

/**
 * The one set of destinations of every signed-in user (UX-DR85, Story 20.6), whatever roles they hold in their
 * Jobs: no role on the account picks them, and there is no mode switcher. Each page shows its gate or its Empty state.
 */
const DESTINATIONS: Destination[] = [
  { label: 'nav.todo', to: '/viec-can-lam', icon: House },
  { label: 'nav.jobs', to: '/cong-viec', icon: BriefcaseBusiness },
  { label: 'nav.findWork', to: '/tim-viec', icon: Search },
  { label: 'nav.products', to: '/san-pham', icon: Package },
  { label: 'nav.notifications', to: '/thong-bao', icon: Bell },
]

/** Under 360 px the tab bar keeps four cells, and "Thông báo" is reached through the bell (UX-DR85). */
const NARROW = '(max-width: 359px)'

/** From 768 px the top bar holds the search and "Tạo", and the sidebar replaces the tab bar. */
const DESKTOP = TOP_SEARCH_QUERY

/** From 1024 px the sidebar is a full column, with the running Jobs and the account pages under the destinations. */
const WIDE = '(min-width: 1024px)'

/**
 * The shell of every signed-in user (UX-DR10 to UX-DR14, UX-DR85, UX-DR88, UX-DR111; the layout of 2026-11-23): a
 * top bar across the window with the name, the search, "Tạo", the bell and the avatar menu; under it, from 768 px,
 * the sidebar with the destinations (a rail of icons until 1024 px, and from there whenever the user folds it with the
 * top bar's button), and on phones a bottom tab bar with counts;
 * the next-action strip (not on Trang chủ, whose list starts with the same to-do), the offline line, and the toasts.
 * The top bar sticks to the top of the window and the sidebar under it. Inside a Job the strip shows that Job's
 * to-dos only; nothing is sized by the account.
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
  const narrow = useMatchMedia(NARROW)
  const desktop = useMatchMedia(DESKTOP)
  const wide = useMatchMedia(WIDE)
  const [collapsed, toggleSidebar] = useSidebarCollapsed()
  const jobId = useMatch('/cong-viec/:jobId/*')?.params.jobId
  // Trang chủ lists the same to-dos first, so the strip would only repeat its first row there.
  const home = useMatch('/viec-can-lam') !== null
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
  const tabs = narrow ? DESTINATIONS.filter((destination) => destination.to !== '/thong-bao') : DESTINATIONS
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
      {/* The top bar stays in view while long pages (a Job, an Agreement) scroll; html's scroll-padding-top keeps
          a focused control from sliding under it (WCAG 2.4.11). */}
      <header className="sticky top-0 z-40 border-b border-border bg-card">
        <div className="flex min-h-header-height flex-wrap items-center gap-x-3 gap-y-2 px-gutter-mobile py-2 md:px-5">
          {/* From 1024 px the sidebar is a full column, and this button folds it into the rail and back. */}
          {wide && (
            <button
              type="button"
              aria-label={t(collapsed ? 'shell.expandSidebar' : 'shell.collapseSidebar')}
              aria-expanded={!collapsed}
              aria-controls={SIDEBAR_ID}
              onClick={toggleSidebar}
              className="-ml-2 flex size-10 shrink-0 items-center justify-center rounded-full text-foreground hover:bg-muted"
            >
              <Menu aria-hidden="true" className="size-5.5" />
            </button>
          )}
          {/* The name sits over the sidebar: with the button, as wide as the sidebar's column from 1024 px. */}
          <Link
            to={landingPath(current)}
            className="text-h2 font-bold tracking-tight text-primary no-underline lg:w-41"
            aria-label={t('shell.home')}
          >
            {t('app.name')}
          </Link>
          {/* The one search of the app from 768 px, on Tìm việc too, which then shows no Search bar of its own. */}
          {desktop ? (
            <div className="flex min-w-0 grow justify-center">
              <TopSearch />
            </div>
          ) : (
            <span className="grow" />
          )}
          {desktop && <CreateMenu />}
          {/* From 360 to 767 px the tab bar carries "Thông báo" with the same count, so the bell would be a second
              door to the same page there (seen 2026-11-24); under 360 px the tab bar drops it and the bell stays. */}
          {(desktop || narrow) && <NotificationBell offline={offline} />}
          <AccountMenu session={current} offline={offline} />
        </div>
      </header>
      {offline && (
        <p role="status" className="border-b border-border bg-muted px-gutter-mobile py-1 text-center text-caption text-muted-foreground">
          {t('state.offline')}
        </p>
      )}
      <div className="flex grow">
        {/* From 768 px the bell holds the notifications, so the sidebar lists four destinations (2026-11-23). */}
        <AppSidebar
          destinations={DESTINATIONS.filter((destination) => destination.to !== '/thong-bao')}
          wide={wide}
          collapsed={collapsed}
        />
        <div className="flex min-w-0 grow flex-col">
          {!home && <NextActionStrip jobId={jobId} />}
          <main id={MAIN_ID} tabIndex={-1} className="grow pb-tab-bar-height focus:outline-none md:pb-0">
            <Outlet />
          </main>
        </div>
      </div>
      <nav
        aria-label={t('shell.tabNav')}
        className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-card pb-[env(safe-area-inset-bottom)] md:hidden"
      >
        <ul className="flex">
          {tabs.map((destination) => {
            const count = counts[destination.to] ?? 0
            return (
              <li key={destination.to} className="flex-1">
                <NavLink
                  to={destination.to}
                  // Where this cell is the only door to "Thông báo", it says the unread count as the bell does.
                  aria-label={
                    destination.to === '/thong-bao' && count > 0
                      ? t('screen.shared.notifications.bellUnread', { count })
                      : undefined
                  }
                  className={({ isActive }) =>
                    cn(
                      'flex min-h-tab-bar-height flex-col items-center justify-center gap-0.5 px-0.5 text-caption',
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
                  <span className="text-center leading-tight">{t(destination.label)}</span>
                </NavLink>
              </li>
            )
          })}
        </ul>
      </nav>
      <SessionExpiredDialog />
      <ScrollRestoration />
    </div>
    </Toaster>
  )
}

