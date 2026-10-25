import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { landingPath } from '@/features/identity/api'
import { useSession } from '@/features/identity/useSession'
import { t } from '@/i18n/t'

/**
 * Keeps signed-in routes for signed-in accounts (AD-19): anyone else goes to PUB-2, carrying the
 * route to return to; a Google sign-in that has not accepted N-10 yet goes to PUB-3 "Chào mừng"; a
 * Mentor that is not approved reaches MEN-5 only (FR-74).
 */
export function RequireSignedIn({ children }: { children: ReactNode }) {
  const session = useSession()
  const location = useLocation()

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
  const current = session.data
  if (current.status === 'SIGNING_UP') {
    return <Navigate to="/bat-dau" replace />
  }
  if (current.status !== 'SIGNED_IN') {
    return <Navigate to="/dang-nhap" replace state={{ returnTo: location.pathname + location.search }} />
  }
  const landing = landingPath(current)
  // Administrators work in the Admin shell only.
  if (landing === '/quan-tri') {
    return <Navigate to="/quan-tri" replace />
  }
  if (landing === '/ho-so' && location.pathname !== '/ho-so') {
    return <Navigate to="/ho-so" replace />
  }
  return <>{children}</>
}
