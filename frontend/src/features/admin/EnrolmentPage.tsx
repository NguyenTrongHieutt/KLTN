import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { Skeleton } from '@/components/Skeleton'
import { Button } from '@/components/ui/button'
import { landingPath } from '@/features/identity/api'
import { useSession } from '@/features/identity/useSession'
import { verificationMessage } from '@/features/identity/verificationMessages'
import { t } from '@/i18n/t'
import { fieldMessage } from '@/lib/formErrors'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useCompleteEnrolment, useEnrolment } from './adminApi'

const MIN_PASSWORD = 12

/**
 * The enrolment link of a new Administrator (`/quan-tri/kich-hoat/:token`, FR-2, AD-19): the
 * authenticator secret to add, then the password, twice, and a code that proves the app works. It
 * signs the Administrator in on success. A used or lapsed link says so.
 */
export function EnrolmentPage() {
  usePageTitle(t('screen.admin.enrolment.title'))
  const { token = '' } = useParams()
  const session = useSession()
  const enrolment = useEnrolment(token)
  const complete = useCompleteEnrolment(token)
  const navigate = useNavigate()
  const [values, setValues] = useState({ password: '', again: '', code: '' })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [refusal, setRefusal] = useState<string>()

  if (session.data?.status === 'SIGNED_IN') {
    return <Navigate to={landingPath(session.data)} replace />
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (values.password.length < MIN_PASSWORD) {
      next.password = t('screen.admin.enrolment.passwordHelper')
    }
    if (values.again !== values.password) {
      next.again = t('screen.admin.enrolment.mismatch')
    }
    if (!/^[0-9]{6}$/.test(values.code)) {
      next.code = fieldMessage('format')
    }
    setErrors(next)
    if (Object.keys(next).length > 0) {
      return
    }
    complete.mutate(
      { password: values.password, code: values.code },
      {
        onSuccess: () => navigate('/quan-tri', { replace: true }),
        onError: (error) => {
          setValues((current) => ({ ...current, code: '' }))
          setRefusal(verificationMessage(error))
        },
      },
    )
  }

  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <header className="flex min-h-14 items-center gap-2 border-b border-border bg-card px-gutter-mobile md:px-gutter-desktop">
        <span className="text-h3 font-bold">vibefix</span>
        <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">{t('screen.admin.badge')}</span>
      </header>
      <main id="main" className="flex flex-1 justify-center px-gutter-mobile py-12">
        <div className="flex w-full max-w-[520px] flex-col gap-4 self-start rounded-lg border border-border bg-card p-6 md:p-8">
          <PageHeading>{t('screen.admin.enrolment.heading')}</PageHeading>
          {enrolment.isPending ? (
            <Skeleton className="h-48 w-full" />
          ) : enrolment.isError || !enrolment.data ? (
            <p role="alert" className="rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
              {isNotFound(enrolment.error) ? t('screen.admin.enrolment.notFound') : verificationMessage(enrolment.error)}
            </p>
          ) : (
            <>
              <p className="text-body-sm text-muted-foreground">
                {t('screen.admin.enrolment.lead', { name: enrolment.data.displayName, email: enrolment.data.email })}
              </p>
              <section className="flex flex-col gap-2">
                <h2 className="text-h3">{t('screen.admin.enrolment.step1')}</h2>
                <p className="text-body-sm">{t('screen.admin.enrolment.step1Body')}</p>
                <p className="text-label">{t('screen.admin.enrolment.secret')}</p>
                <p className="rounded-md bg-muted p-3 font-mono text-body break-all tracking-wider">
                  {enrolment.data.secret}
                </p>
                <a href={enrolment.data.otpauthUri} className="text-body-sm text-primary underline">
                  {t('screen.admin.enrolment.openApp')}
                </a>
              </section>
              <form noValidate onSubmit={submit} className="flex flex-col gap-4">
                <h2 className="text-h3">{t('screen.admin.enrolment.step2')}</h2>
                {refusal && (
                  <p role="alert" className="rounded-md border-l-4 border-warning bg-warning-soft p-3 text-body-sm">
                    {refusal}
                  </p>
                )}
                <FormField
                  id="enrolPassword"
                  label={t('screen.admin.enrolment.password')}
                  helper={t('screen.admin.enrolment.passwordHelper')}
                  error={errors.password}
                >
                  <input
                    {...fieldProps('enrolPassword', errors.password, t('screen.admin.enrolment.passwordHelper'))}
                    type="password"
                    autoComplete="new-password"
                    className={inputClass}
                    value={values.password}
                    onChange={(event) => setValues({ ...values, password: event.target.value })}
                  />
                </FormField>
                <FormField id="enrolAgain" label={t('screen.admin.enrolment.passwordAgain')} error={errors.again}>
                  <input
                    {...fieldProps('enrolAgain', errors.again)}
                    type="password"
                    autoComplete="new-password"
                    className={inputClass}
                    value={values.again}
                    onChange={(event) => setValues({ ...values, again: event.target.value })}
                  />
                </FormField>
                <FormField id="enrolCode" label={t('screen.admin.enrolment.code')} error={errors.code}>
                  <input
                    {...fieldProps('enrolCode', errors.code)}
                    type="text"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={6}
                    className={cn(inputClass, 'max-w-[200px] font-mono tracking-[0.3em]')}
                    value={values.code}
                    onChange={(event) => setValues({ ...values, code: event.target.value.replace(/[^0-9]/g, '') })}
                  />
                </FormField>
                <Button type="submit" disabled={complete.isPending}>
                  {complete.isPending ? t('screen.admin.enrolment.submitting') : t('screen.admin.enrolment.submit')}
                </Button>
              </form>
            </>
          )}
        </div>
      </main>
    </div>
  )
}
