import { Dialog } from '@base-ui/react/dialog'
import { Menu } from '@base-ui/react/menu'
import { Menu as MenuIcon, X } from 'lucide-react'
import { useState } from 'react'
import { Navigate, NavLink, Outlet, useNavigate } from 'react-router'
import { MAIN_ID } from '@/app/PublicLayout'
import { useFocusHeadingOnNavigate } from '@/app/useFocusHeadingOnNavigate'
import { Toaster } from '@/components/Toaster'
import { buttonVariants } from '@/components/ui/button-variants'
import type { Session } from '@/features/identity/api'
import { SessionExpiredDialog } from '@/features/identity/SessionExpiredDialog'
import { useSession, useSignOut } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { useAdminOverview } from './adminApi'
import { ADMIN_DESTINATIONS } from './adminNavigation'
import { AdminSessionWatcher } from './AdminSessionWatcher'

/**
 * The Admin shell (UX-DR13, SG-10), at `/quan-tri`: a 240 px sidebar from 1024 px and a sheet below
 * it, the account menu, and the session watcher. Anyone not signed in goes to PUB-6; another role
 * finds nothing here.
 */
export function AdminLayout() {
  const session = useSession()
  if (session.isPending) {
    return <p className="p-gutter-mobile text-body-sm text-muted-foreground">{t('state.loading')}</p>
  }
  if (session.isError) {
    return (
      <div className="flex flex-col items-start gap-2 p-gutter-mobile" role="alert">
        <p>{t('error.unexpected')}</p>
        <button type="button" className="text-primary underline" onClick={() => session.refetch()}>
          {t('state.retry')}
        </button>
      </div>
    )
  }
  if (session.data.status !== 'SIGNED_IN') {
    return <Navigate to="/quan-tri/dang-nhap" replace />
  }
  if (session.data.account?.kind !== 'ADMINISTRATOR') {
    return <NotFoundScreen />
  }
  return <AdminShell session={session.data} />
}

function AdminShell({ session }: { session: Session }) {
  useFocusHeadingOnNavigate(MAIN_ID)
  const [sheet, setSheet] = useState(false)
  const name = session.administrator?.displayName ?? session.administrator?.email ?? ''

  return (
    <Toaster>
      <div className="flex min-h-dvh bg-background">
        <a
          href={`#${MAIN_ID}`}
          className={cn(
            buttonVariants({ variant: 'secondary', size: 'compact' }),
            'sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50',
          )}
        >
          {t('shell.skipToContent')}
        </a>
        <aside className="hidden w-60 shrink-0 border-r border-border bg-card lg:block">
          <Sidebar />
        </aside>
        <div className="flex min-w-0 grow flex-col">
          <header className="flex min-h-14 items-center gap-3 border-b border-border bg-card px-gutter-mobile md:px-gutter-desktop">
            <Dialog.Root open={sheet} onOpenChange={setSheet}>
              <Dialog.Trigger
                aria-label={t('screen.admin.shell.openMenu')}
                className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'px-2 lg:hidden')}
              >
                <MenuIcon aria-hidden="true" />
              </Dialog.Trigger>
              <Dialog.Portal>
                <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50 lg:hidden" />
                <Dialog.Popup className="fixed inset-y-0 left-0 z-50 w-60 border-r border-border bg-card lg:hidden">
                  <Dialog.Title className="sr-only">{t('screen.admin.shell.navLabel')}</Dialog.Title>
                  <Dialog.Close
                    aria-label={t('screen.admin.shell.closeMenu')}
                    className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'absolute top-2 right-2 px-2')}
                  >
                    <X aria-hidden="true" />
                  </Dialog.Close>
                  <Sidebar onNavigate={() => setSheet(false)} />
                </Dialog.Popup>
              </Dialog.Portal>
            </Dialog.Root>
            <span className="grow" />
            <AdminAccountMenu name={name} />
          </header>
          <main id={MAIN_ID} tabIndex={-1} className="grow focus:outline-none">
            <Outlet />
          </main>
        </div>
        <AdminSessionWatcher />
        <SessionExpiredDialog />
      </div>
    </Toaster>
  )
}

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const overview = useAdminOverview()
  return (
    <nav aria-label={t('screen.admin.shell.navLabel')} className="flex flex-col gap-0.5 px-3 py-4">
      <p className="flex items-center gap-2 px-3 pb-4">
        <span className="text-h3 font-bold text-primary">{t('app.name')}</span>
        <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">{t('screen.admin.badge')}</span>
      </p>
      {ADMIN_DESTINATIONS.map((destination) => (
        <NavLink
          key={destination.to}
          to={destination.to}
          end
          onClick={onNavigate}
          className={({ isActive }) =>
            cn(
              'flex min-h-control-compact items-center gap-2 rounded-md px-3 text-label',
              isActive ? 'bg-primary-soft text-foreground shadow-[inset_3px_0_0_var(--primary)]' : 'hover:bg-muted',
            )
          }
        >
          <destination.icon aria-hidden="true" className="size-4" />
          {t(destination.label)}
          {destination.count && <QueueCount count={overview.data?.[destination.count]?.count} />}
        </NavLink>
      ))}
    </nav>
  )
}

/** A queue's waiting count beside its sidebar label, while there is one (UX-DR13). */
function QueueCount({ count }: { count?: number }) {
  if (!count) {
    return null
  }
  return (
    <span className="ml-auto rounded-full bg-destructive px-2 text-caption font-semibold text-destructive-foreground">
      <span aria-hidden="true">{count}</span>
      <span className="sr-only">{t('screen.admin.nav.pendingCount', { n: count })}</span>
    </span>
  )
}

function AdminAccountMenu({ name }: { name: string }) {
  const signOut = useSignOut()
  const navigate = useNavigate()
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part[0])
    .join('')
    .slice(-2)
    .toUpperCase()

  return (
    <Menu.Root>
      <Menu.Trigger
        aria-label={t('screen.admin.shell.account', { name })}
        className="flex size-9 items-center justify-center rounded-full bg-primary-soft text-label text-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
      >
        {initials}
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner align="end" sideOffset={8} className="z-50">
          <Menu.Popup className="min-w-48 rounded-md border border-border bg-popover p-1 text-popover-foreground shadow-md">
            <p className="px-3 py-2 text-caption text-muted-foreground">{name}</p>
            <Menu.Item
              onClick={() => signOut.mutate(undefined, { onSettled: () => navigate('/quan-tri/dang-nhap', { replace: true }) })}
              className="flex min-h-control-compact cursor-pointer items-center rounded-sm px-3 text-body-sm data-highlighted:bg-muted"
            >
              {t('screen.admin.shell.signOut')}
            </Menu.Item>
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}
