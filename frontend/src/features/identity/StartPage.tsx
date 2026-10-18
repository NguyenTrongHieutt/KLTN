import { TriangleAlert } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { landingPath, type SignUpRole } from './api'
import { useSession, useSignOut, useSignUp } from './useSession'

const ROLES: { role: SignUpRole; label: MessageKey; body: MessageKey }[] = [
  { role: 'CUSTOMER', label: 'screen.public.start.customer', body: 'screen.public.start.customerBody' },
  { role: 'MENTOR', label: 'screen.public.start.mentor', body: 'screen.public.start.mentorBody' },
  { role: 'STUDENT', label: 'screen.public.start.student', body: 'screen.public.start.studentBody' },
]

const NOTICE: { title: MessageKey; body: MessageKey }[] = [
  { title: 'screen.public.start.noticeCollectedTitle', body: 'screen.public.start.noticeCollected' },
  { title: 'screen.public.start.noticePurposeTitle', body: 'screen.public.start.noticePurpose' },
  { title: 'screen.public.start.noticeRetentionTitle', body: 'screen.public.start.noticeRetention' },
  { title: 'screen.public.start.noticeRightsTitle', body: 'screen.public.start.noticeRights' },
]

/**
 * PUB-3 (`/bat-dau`), first sign-in only (FR-1, FR-74, NFR-9): the one permanent choice of role, and
 * the personal-data notice N-10, which must be accepted; the accepted version is the one the server
 * named, so a notice changed meanwhile answers STALE.
 */
export function StartPage() {
  usePageTitle(t('screen.public.start.title'))
  const session = useSession()
  const signUp = useSignUp()
  const signOut = useSignOut()
  const navigate = useNavigate()
  const [role, setRole] = useState<SignUpRole | null>(null)
  // The version the box was ticked for: a notice changed meanwhile (STALE) needs ticking again.
  const [acceptedVersion, setAcceptedVersion] = useState<string | null>(null)

  if (session.isPending) {
    return <p className="p-gutter-mobile text-body-sm text-muted-foreground">{t('state.loading')}</p>
  }
  const current = session.data
  if (current?.status === 'SIGNED_IN') {
    return <Navigate to={landingPath(current)} replace />
  }
  if (current?.status !== 'SIGNING_UP') {
    return <Navigate to="/dang-nhap" replace />
  }
  const login = current.githubLogin ?? ''
  const version = current.privacyNoticeVersion
  const accepted = acceptedVersion === version

  function submit(event: FormEvent) {
    event.preventDefault()
    if (role === null || !accepted) {
      return
    }
    signUp.mutate(
      { role, privacyNoticeVersion: version },
      {
        onSuccess: (signedIn) => navigate(landingPath(signedIn), { replace: true }),
        onError: () => session.refetch(),
      },
    )
  }

  return (
    <form
      onSubmit={submit}
      className="mx-auto flex max-w-reading-max flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop"
    >
      <p className="text-body-sm text-muted-foreground">{t('screen.public.start.signedInAs', { login })}</p>
      <div className="flex flex-col gap-2">
        <PageHeading>{t('screen.public.start.heading')}</PageHeading>
        <p className="text-muted-foreground">{t('screen.public.start.lead')}</p>
      </div>
      <fieldset className="flex min-w-0 flex-col gap-3">
        <legend className="mb-2 text-label">{t('screen.public.start.roleLegend')}</legend>
        {ROLES.map((option) => (
          <label
            key={option.role}
            className={cn(
              'flex cursor-pointer gap-3 rounded-lg border bg-card p-4',
              role === option.role ? 'border-2 border-primary bg-primary-soft' : 'border-border',
            )}
          >
            <input
              type="radio"
              name="role"
              value={option.role}
              checked={role === option.role}
              onChange={() => setRole(option.role)}
              className="mt-1 size-5 shrink-0 accent-primary"
            />
            <span className="flex flex-col gap-0.5">
              <span className="font-semibold">{t(option.label)}</span>
              <span className="text-body-sm text-muted-foreground">{t(option.body)}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <div className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-warning" />
        <p className="text-body-sm">{t('screen.public.start.permanent', { login })}</p>
      </div>
      <section className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4" aria-labelledby="thong-bao-du-lieu">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="thong-bao-du-lieu" className="text-h2">
            {t('screen.public.start.noticeHeading')}
          </h2>
          <span className="text-caption text-muted-foreground">{t('screen.public.start.noticeVersion', { version })}</span>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          {NOTICE.map((part) => (
            <div key={part.title} className="flex flex-col gap-0.5">
              <p className="text-label font-semibold">{t(part.title)}</p>
              <p className="text-body-sm text-muted-foreground">{t(part.body)}</p>
            </div>
          ))}
        </div>
        <Link
          to="/quyen-rieng-tu"
          target="_blank"
          rel="noopener noreferrer"
          className="text-body-sm text-primary underline-offset-4 hover:underline"
        >
          {t('screen.public.start.noticeFull')}
        </Link>
      </section>
      <label className="flex cursor-pointer gap-3">
        <input
          type="checkbox"
          checked={accepted}
          onChange={(event) => setAcceptedVersion(event.target.checked ? version : null)}
          className="mt-0.5 size-5 shrink-0 accent-primary"
        />
        <span className="text-body-sm">{t('screen.public.start.accept', { version })}</span>
      </label>
      {signUp.isError && (
        <p role="alert" className="rounded-md border-l-4 border-destructive bg-destructive-soft p-4 text-body-sm">
          {problemMessage(signUp.error).text}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button
          type="button"
          variant="ghost"
          onClick={() => signOut.mutate(undefined, { onSuccess: () => navigate('/', { replace: true }) })}
        >
          {t('screen.public.start.signOut')}
        </Button>
        <Button type="submit" disabled={role === null || !accepted || signUp.isPending}>
          {signUp.isPending ? t('screen.public.start.saving') : t('screen.public.start.continue')}
        </Button>
      </div>
    </form>
  )
}
