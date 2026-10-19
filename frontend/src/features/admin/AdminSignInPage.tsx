import { CircleAlert, LockKeyhole } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { z } from 'zod'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { Button } from '@/components/ui/button'
import { landingPath } from '@/features/identity/api'
import { useSession } from '@/features/identity/useSession'
import { identityReason, verificationMessage } from '@/features/identity/verificationMessages'
import { t } from '@/i18n/t'
import { fieldMessage, zodFieldErrors } from '@/lib/formErrors'
import { cn } from '@/lib/utils'
import { useAdminSignIn } from './adminApi'

const schema = z.object({
  email: z.string().trim().min(1, 'required').pipe(z.email('email')),
  password: z.string().min(1, 'required'),
  code: z.string().trim().regex(/^[0-9]{6}$/, 'format'),
})

type Field = keyof z.input<typeof schema>

/**
 * PUB-6 (`/quan-tri/dang-nhap`, FR-2, AD-19): the Administrator sign-in, linked from no public page.
 * Email, password, and the authenticator's 6-digit code; any wrong part reads the same, and 5 wrong
 * attempts in 15 minutes lock the email for 15 minutes ("Tạm khóa đăng nhập 15 phút").
 */
export function AdminSignInPage() {
  usePageTitle(t('screen.admin.signIn.title'))
  const session = useSession()
  const navigate = useNavigate()
  const signIn = useAdminSignIn()
  const [values, setValues] = useState({ email: '', password: '', code: '' })
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [refusal, setRefusal] = useState<{ locked: boolean; text: string }>()

  if (session.data?.status === 'SIGNED_IN') {
    return <Navigate to={landingPath(session.data)} replace />
  }

  function set(field: Field, value: string) {
    setValues((current) => ({ ...current, [field]: value }))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const result = schema.safeParse(values)
    if (!result.success) {
      setErrors(zodFieldErrors(result.error))
      return
    }
    setErrors({})
    signIn.mutate(result.data, {
      onSuccess: () => navigate('/quan-tri', { replace: true }),
      onError: (error) => {
        // A code is used once, so a refused one is cleared.
        set('code', '')
        const locked = identityReason(error) === 'ADMIN_SIGN_IN_LOCKED'
        setRefusal({ locked, text: locked ? t('screen.admin.signIn.lockedBody') : verificationMessage(error) })
      },
    })
  }

  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <header className="flex min-h-14 items-center gap-2 border-b border-border bg-card px-gutter-mobile md:px-gutter-desktop">
        <span className="text-h3 font-bold">vibefix</span>
        <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">{t('screen.admin.badge')}</span>
      </header>
      <main id="main" className="flex flex-1 justify-center px-gutter-mobile py-16">
        <form
          noValidate
          onSubmit={submit}
          className="flex w-full max-w-[420px] flex-col gap-4 self-start rounded-lg border border-border bg-card p-6 md:p-8"
        >
          <div className="flex flex-col gap-2">
            <PageHeading>{t('screen.admin.signIn.heading')}</PageHeading>
            <p className="text-body-sm text-muted-foreground">{t('screen.admin.signIn.lead')}</p>
          </div>
          {refusal && (
            <div
              role="alert"
              className={cn(
                'flex gap-3 rounded-md border-l-4 p-4',
                refusal.locked ? 'border-destructive bg-destructive-soft' : 'border-warning bg-warning-soft',
              )}
            >
              {refusal.locked ? (
                <LockKeyhole aria-hidden="true" className="size-5 shrink-0 text-destructive" />
              ) : (
                <CircleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
              )}
              <div className="flex flex-col gap-0.5">
                {refusal.locked && <p className="text-label font-semibold">{t('screen.admin.signIn.lockedTitle')}</p>}
                <p className="text-body-sm">{refusal.text}</p>
              </div>
            </div>
          )}
          <FormField id="adminEmail" label={t('screen.admin.signIn.email')} error={errors.email}>
            <input
              {...fieldProps('adminEmail', errors.email)}
              type="email"
              autoComplete="username"
              className={inputClass}
              value={values.email}
              onChange={(event) => set('email', event.target.value)}
            />
          </FormField>
          <FormField id="adminPassword" label={t('screen.admin.signIn.password')} error={errors.password}>
            <input
              {...fieldProps('adminPassword', errors.password)}
              type="password"
              autoComplete="current-password"
              className={inputClass}
              value={values.password}
              onChange={(event) => set('password', event.target.value)}
            />
          </FormField>
          <FormField
            id="adminCode"
            label={t('screen.admin.signIn.code')}
            helper={t('screen.admin.signIn.codeHelper')}
            error={errors.code ? fieldMessage('format') : undefined}
          >
            <input
              {...fieldProps('adminCode', errors.code, t('screen.admin.signIn.codeHelper'))}
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              className={cn(inputClass, 'font-mono tracking-[0.3em]')}
              value={values.code}
              onChange={(event) => set('code', event.target.value.replace(/[^0-9]/g, ''))}
            />
          </FormField>
          <Button type="submit" disabled={signIn.isPending} className="w-full">
            {signIn.isPending ? t('screen.admin.signIn.submitting') : t('screen.admin.signIn.submit')}
          </Button>
        </form>
      </main>
    </div>
  )
}
