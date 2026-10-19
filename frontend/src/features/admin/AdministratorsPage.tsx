import { Dialog } from '@base-ui/react/dialog'
import { TriangleAlert, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { z } from 'zod'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate, formatDayMonthTime, formatTime } from '@/lib/dates'
import { zodFieldErrors } from '@/lib/formErrors'
import { cn } from '@/lib/utils'
import { useAdministrators, useCreateAdministrator, type AdministratorRow } from './adminApi'
import { StepUpDialog } from './StepUpDialog'

const schema = z.object({
  displayName: z.string().trim().min(1, 'required').max(100, 'tooLong'),
  email: z.string().trim().min(1, 'required').max(254, 'tooLong').pipe(z.email('email')),
})

const STATUS: Record<string, { label: MessageKey; tone: string }> = {
  ACTIVE: { label: 'screen.admin.administrators.active', tone: 'bg-success-soft text-success' },
  ENROLLING: { label: 'screen.admin.administrators.enrolling', tone: 'bg-warning-soft text-warning' },
  EXPIRED: { label: 'screen.admin.administrators.expired', tone: 'bg-muted text-muted-foreground' },
}

/**
 * ADM-11 (`/quan-tri/quan-tri-vien`, FR-2, SG-10): the Administrators with their two-factor state and
 * last sign-in, and "Tạo quản trị viên": the form, the confirmation of what will happen, then the
 * Step-up dialog. Every Administrator is alerted, and the new one gets the enrolment link by email.
 */
export function AdministratorsPage() {
  usePageTitle(t('screen.admin.administrators.title'))
  const administrators = useAdministrators()

  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.administrators.heading')}</PageHeading>
      <div className="flex flex-wrap items-start gap-6">
        <div className="min-w-0 flex-[999_1_560px]">
          <RegionState query={administrators} skeleton={<Skeleton className="h-40 w-full" />}>
            <AdministratorTable rows={administrators.data ?? []} />
          </RegionState>
        </div>
        <aside className="flex-[1_1_300px]">
          <CreateAdministrator />
        </aside>
      </div>
    </div>
  )
}

function AdministratorTable({ rows }: { rows: AdministratorRow[] }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-border bg-card">
      <table className="w-full text-left text-body-sm" aria-label={t('screen.admin.administrators.listLabel')}>
        <thead className="border-b border-border text-caption text-muted-foreground">
          <tr>
            <th className="px-4 py-2 font-semibold">{t('screen.admin.administrators.name')}</th>
            <th className="px-4 py-2 font-semibold">{t('screen.admin.administrators.email')}</th>
            <th className="px-4 py-2 font-semibold">{t('screen.admin.administrators.twoFactor')}</th>
            <th className="px-4 py-2 font-semibold">{t('screen.admin.administrators.createdOn')}</th>
            <th className="px-4 py-2 font-semibold">{t('screen.admin.administrators.lastSignIn')}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {rows.map((row) => {
            const status = STATUS[row.status] ?? STATUS.EXPIRED
            return (
              <tr key={row.id}>
                <td className="px-4 py-3 font-semibold">
                  {row.you ? t('screen.admin.administrators.you', { name: row.displayName }) : row.displayName}
                </td>
                <td className="px-4 py-3">{row.email}</td>
                <td className="px-4 py-3">
                  <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', status.tone)}>{t(status.label)}</span>
                </td>
                <td className="px-4 py-3">{formatDate(row.createdAt)}</td>
                <td className="px-4 py-3">
                  {row.lockedUntil ? (
                    <span className="rounded-full bg-destructive-soft px-2 py-0.5 text-caption font-semibold text-destructive">
                      {t('screen.admin.administrators.lockedUntil', { time: formatTime(row.lockedUntil) })}
                    </span>
                  ) : row.signedInAt ? (
                    formatDayMonthTime(row.signedInAt)
                  ) : (
                    t('screen.admin.administrators.neverSignedIn')
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

type Step = 'form' | 'confirm' | 'stepUp'

function CreateAdministrator() {
  const [values, setValues] = useState({ displayName: '', email: '' })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [step, setStep] = useState<Step>('form')
  const create = useCreateAdministrator()
  const toasts = useToasts()

  function review(event: FormEvent) {
    event.preventDefault()
    const result = schema.safeParse(values)
    if (!result.success) {
      setErrors(zodFieldErrors(result.error))
      return
    }
    setErrors({})
    setValues(result.data)
    setStep('confirm')
  }

  return (
    <>
      <form noValidate onSubmit={review} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4 md:p-6">
        <h2 className="text-h3">{t('screen.admin.administrators.formHeading')}</h2>
        <FormField id="newDisplayName" label={t('screen.admin.administrators.displayName')} error={errors.displayName}>
          <input
            {...fieldProps('newDisplayName', errors.displayName)}
            className={inputClass}
            placeholder={t('screen.admin.administrators.displayNamePlaceholder')}
            maxLength={100}
            value={values.displayName}
            onChange={(event) => setValues({ ...values, displayName: event.target.value })}
          />
        </FormField>
        <FormField
          id="newEmail"
          label={t('screen.admin.administrators.workEmail')}
          helper={t('screen.admin.administrators.workEmailHelper')}
          error={errors.email}
        >
          <input
            {...fieldProps('newEmail', errors.email, t('screen.admin.administrators.workEmailHelper'))}
            type="email"
            className={inputClass}
            placeholder="ten@vibefix.vn"
            value={values.email}
            onChange={(event) => setValues({ ...values, email: event.target.value })}
          />
        </FormField>
        <p className="flex gap-2 rounded-md border-l-4 border-warning bg-warning-soft p-3 text-body-sm">
          <TriangleAlert aria-hidden="true" className="size-4 shrink-0 text-warning" />
          {t('screen.admin.administrators.notice')}
        </p>
        <Button type="submit">{t('screen.admin.administrators.create')}</Button>
      </form>
      <Dialog.Root open={step === 'confirm'} onOpenChange={(open) => !open && setStep('form')}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-4">
              <Dialog.Title className="text-h2">{t('screen.admin.administrators.confirmTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.admin.administrators.close')}
                className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'px-2')}
              >
                <X aria-hidden="true" />
              </Dialog.Close>
            </div>
            <dl className="flex flex-col gap-2 text-body-sm">
              <div>
                <dt className="text-label">{t('screen.admin.administrators.confirmName')}</dt>
                <dd>{values.displayName}</dd>
              </div>
              <div>
                <dt className="text-label">{t('screen.admin.administrators.email')}</dt>
                <dd>{values.email}</dd>
                <dd className="text-caption text-muted-foreground">{t('screen.admin.administrators.confirmEmailHelper')}</dd>
              </div>
            </dl>
            <div className="flex flex-col gap-1">
              <p className="text-label font-semibold">{t('screen.admin.administrators.whatHappens')}</p>
              <ul className="list-disc pl-5 text-body-sm">
                <li>{t('screen.admin.administrators.happens1')}</li>
                <li>{t('screen.admin.administrators.happens2')}</li>
                <li>{t('screen.admin.administrators.happens3')}</li>
              </ul>
            </div>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.admin.administrators.cancel')}</Dialog.Close>
              <Button onClick={() => setStep('stepUp')}>{t('screen.admin.administrators.continue')}</Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
      <StepUpDialog
        open={step === 'stepUp'}
        onOpenChange={(open) => !open && setStep('confirm')}
        confirmLabel={t('screen.admin.administrators.create')}
        pending={create.isPending}
        onConfirm={(code, refused) =>
          create.mutate(
            { ...values, stepUpCode: code },
            {
              onSuccess: () => {
                toasts.success(t('screen.admin.administrators.createdToast', { email: values.email }))
                setValues({ displayName: '', email: '' })
                setStep('form')
              },
              onError: refused,
            },
          )
        }
      />
    </>
  )
}
