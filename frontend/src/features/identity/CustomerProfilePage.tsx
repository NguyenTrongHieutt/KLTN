import { useState, type FormEvent } from 'react'
import { z } from 'zod'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { usePollHealth } from '@/app/usePollHealth'
import { ErrorSummary } from '@/components/ErrorSummary'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { fieldMessage, serverFieldErrors, zodFieldErrors } from '@/lib/formErrors'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useProfile, useSaveCustomerProfile, type CustomerProfile, type Profile } from './profileApi'

const schema = z.object({
  kind: z.enum(['INDIVIDUAL', 'BUSINESS']),
  displayName: z.string().trim().min(1, 'required').max(100, 'tooLong'),
  contactEmail: z.string().trim().min(1, 'required').max(254, 'tooLong').pipe(z.email('email')),
  phone: z
    .string()
    .trim()
    .regex(/^(?:\+?(?:[0-9] ?){7,14}[0-9])?$/, 'format'),
})

type Field = 'displayName' | 'contactEmail' | 'phone'

const LABELS: Record<Field, string> = {
  displayName: t('screen.customer.profile.displayName'),
  contactEmail: t('screen.customer.profile.contactEmail'),
  phone: t('screen.customer.profile.phone'),
}

/**
 * CUS-11 (`/ho-so`), the Customer's profile (FR-3): individual or business, display name, contact
 * email, and an optional phone. Fields validate on blur and on submit; the server's field errors
 * map by name; the input stays on any failure (UX-DR44).
 */
export function CustomerProfilePage() {
  usePageTitle(t('screen.customer.profile.title'))
  const profile = useProfile()

  return (
    <div className="mx-auto flex max-w-reading-max flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.customer.profile.heading')}</PageHeading>
      <RegionState
        query={profile}
        skeleton={
          <div className="flex flex-col gap-4">
            {[0, 1, 2, 3].map((row) => (
              <Skeleton key={row} className="h-16 w-full" />
            ))}
          </div>
        }
      >
        {profile.data && <CustomerForm profile={profile.data} />}
      </RegionState>
    </div>
  )
}

function CustomerForm({ profile }: { profile: Profile }) {
  const saved = profile.customer
  const [values, setValues] = useState<CustomerProfile>({
    kind: saved?.kind ?? 'INDIVIDUAL',
    displayName: saved?.displayName ?? '',
    contactEmail: saved?.contactEmail ?? '',
    phone: saved?.phone ?? '',
  })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [summary, setSummary] = useState(false)
  const [savedAt, setSavedAt] = useState<string | null>(null)
  const save = useSaveCustomerProfile()
  const toasts = useToasts()
  const { offline } = usePollHealth()

  function set(field: keyof CustomerProfile, value: string) {
    setValues((current) => ({ ...current, [field]: value }))
  }

  function validate(field: Field) {
    const result = schema.shape[field].safeParse(values[field] ?? '')
    setErrors((current) => {
      const next = { ...current }
      if (result.success) {
        delete next[field]
      } else {
        next[field] = fieldMessage(result.error.issues[0]?.message ?? 'format')
      }
      return next
    })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const result = schema.safeParse(values)
    if (!result.success) {
      setErrors(zodFieldErrors(result.error))
      setSummary(true)
      return
    }
    const body = { ...result.data, phone: result.data.phone === '' ? undefined : result.data.phone }
    save.mutate(body, {
      onSuccess: () => {
        setErrors({})
        setSummary(false)
        setSavedAt(new Date().toISOString())
        toasts.success(t('screen.customer.profile.saved'))
      },
      onError: (error) => {
        const fields = serverFieldErrors(error)
        setErrors(fields)
        setSummary(Object.keys(fields).length > 0)
        toasts.error(problemMessage(error).text)
      },
    })
  }

  const listed = (Object.keys(LABELS) as Field[])
    .filter((field) => errors[field])
    .map((field) => ({ id: field, label: LABELS[field], message: errors[field] }))

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-5">
      {summary && <ErrorSummary errors={listed} />}
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-label">{t('screen.customer.profile.kind')}</legend>
        <div className="flex flex-wrap gap-3">
          {(['INDIVIDUAL', 'BUSINESS'] as const).map((kind) => (
            <label
              key={kind}
              className={cn(
                'flex min-h-tap-min cursor-pointer items-center gap-2 rounded-md border px-3',
                values.kind === kind ? 'border-primary bg-primary-soft' : 'border-border',
              )}
            >
              <input
                type="radio"
                name="kind"
                value={kind}
                checked={values.kind === kind}
                onChange={() => set('kind', kind)}
                className="size-5 accent-primary"
              />
              {t(kind === 'INDIVIDUAL' ? 'screen.customer.profile.individual' : 'screen.customer.profile.business')}
            </label>
          ))}
        </div>
      </fieldset>
      <FormField id="displayName" label={LABELS.displayName} error={errors.displayName}>
        <input
          {...fieldProps('displayName', errors.displayName)}
          className={inputClass}
          value={values.displayName}
          maxLength={100}
          onChange={(event) => set('displayName', event.target.value)}
          onBlur={() => validate('displayName')}
        />
      </FormField>
      <FormField
        id="contactEmail"
        label={LABELS.contactEmail}
        helper={t('screen.customer.profile.contactHelper')}
        error={errors.contactEmail}
      >
        <input
          {...fieldProps('contactEmail', errors.contactEmail, t('screen.customer.profile.contactHelper'))}
          type="email"
          autoComplete="email"
          className={inputClass}
          value={values.contactEmail}
          onChange={(event) => set('contactEmail', event.target.value)}
          onBlur={() => validate('contactEmail')}
        />
      </FormField>
      <FormField id="phone" label={LABELS.phone} helper={t('screen.customer.profile.phoneHelper')} error={errors.phone}>
        <input
          {...fieldProps('phone', errors.phone, t('screen.customer.profile.phoneHelper'))}
          type="tel"
          autoComplete="tel"
          className={inputClass}
          value={values.phone ?? ''}
          onChange={(event) => set('phone', event.target.value)}
          onBlur={() => validate('phone')}
        />
      </FormField>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={save.isPending || offline}>
          {save.isPending ? t('screen.customer.profile.saving') : t('screen.customer.profile.save')}
        </Button>
        {savedAt && (
          <p role="status" className="text-body-sm text-muted-foreground">
            {t('screen.customer.profile.savedAt', { time: formatDayMonthTime(savedAt) })}
          </p>
        )}
      </div>
    </form>
  )
}
