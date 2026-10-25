import { Lock } from 'lucide-react'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { buttonVariants } from '@/components/ui/button-variants'
import { TermHint, TermScope } from '@/components/TermHint'
import { rich } from '@/i18n/rich'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { landingPath, signInUrl } from './api'
import { useSession } from './useSession'

const ERRORS: Record<string, MessageKey> = {
  'that-bai': 'screen.public.signIn.failed',
  'chua-thiet-lap': 'screen.public.signIn.notConfigured',
  'email-chua-xac-minh': 'screen.public.signIn.emailNotVerified',
}

/**
 * PUB-2 (`/dang-nhap`): sign in with Google, the only way in for users (FR-1, Story 19.1); GitHub is
 * linked later, where it is first needed. An email Google has not verified is refused, and a locked
 * account sees why it was refused and whom to contact (FR-59). A route that sent the visitor here is
 * the one they return to.
 */
export function SignInPage() {
  usePageTitle(t('screen.public.signIn.title'))
  const session = useSession()
  const [search] = useSearchParams()
  const location = useLocation()
  const returnTo = (location.state as { returnTo?: string } | null)?.returnTo

  if (session.data?.status === 'SIGNED_IN') {
    return <Navigate to={landingPath(session.data)} replace />
  }
  if (session.data?.status === 'SIGNING_UP') {
    return <Navigate to="/bat-dau" replace />
  }
  const error = ERRORS[search.get('loi') ?? '']
  const locked = session.data?.status === 'LOCKED' ? session.data : null

  return (
    <TermScope>
      <div className="flex justify-center px-gutter-mobile py-section-gap">
        <section className="flex w-full max-w-[440px] flex-col gap-5 rounded-lg border border-border bg-card p-6 md:p-8">
          <div className="flex flex-col gap-2">
            <PageHeading>{t('screen.public.signIn.heading')}</PageHeading>
            <p className="text-body-sm text-muted-foreground">
              {rich('screen.public.signIn.lead', { github: <TermHint term="github" /> })}
            </p>
          </div>
          {locked && (
            <div role="alert" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4">
              <Lock aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-destructive" />
              <div className="flex flex-col gap-1">
                <p className="text-label font-semibold">{t('screen.public.signIn.lockedTitle')}</p>
                <p className="text-body-sm">{t('screen.public.signIn.lockedReason', { reason: locked.lockReason ?? '' })}</p>
                <p className="text-body-sm">{t('screen.public.signIn.lockedContact', { email: locked.supportEmail ?? '' })}</p>
              </div>
            </div>
          )}
          {error && (
            <p role="alert" className="rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
              {t(error)}
            </p>
          )}
          <a href={signInUrl(returnTo)} className={cn(buttonVariants(), 'w-full')}>
            {t('screen.public.signIn.button')}
          </a>
          <hr className="border-border" />
          <div className="flex flex-col gap-2">
            <p className="text-body-sm">
              <span className="font-semibold">{t('screen.public.signIn.firstTime')}</span>{' '}
              {t('screen.public.signIn.firstTimeBody')}
            </p>
            <p className="text-caption text-muted-foreground">
              {rich('screen.public.signIn.privacy', {
                link: (
                  <Link to="/quyen-rieng-tu" className="text-primary underline-offset-4 hover:underline">
                    {t('screen.public.signIn.privacyLink')}
                  </Link>
                ),
              })}
            </p>
          </div>
        </section>
      </div>
    </TermScope>
  )
}
