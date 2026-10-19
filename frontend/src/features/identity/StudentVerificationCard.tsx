import { useState, type FormEvent } from 'react'
import { z } from 'zod'
import { usePollHealth } from '@/app/usePollHealth'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDate, formatDate, formatTime } from '@/lib/dates'
import { fieldMessage } from '@/lib/formErrors'
import { cn } from '@/lib/utils'
import {
  useConfirmSchoolEmailCode,
  useSendSchoolEmailCode,
  useVerification,
  type Verification,
} from './verificationApi'
import { identityReason, verificationMessage } from './verificationMessages'

const email = z.string().trim().min(1, 'required').max(254, 'tooLong').pipe(z.email('email'))

const code = z.string().trim().regex(/^[0-9]{6}$/, 'format')

const BADGE: Record<Verification['status'], { label: MessageKey; tone: string }> = {
  UNVERIFIED: { label: 'screen.student.verification.unverified', tone: 'bg-warning-soft text-warning' },
  VERIFIED: { label: 'screen.student.verification.verified', tone: 'bg-success-soft text-success' },
  EXPIRED: { label: 'screen.student.verification.expired', tone: 'bg-warning-soft text-warning' },
}

/**
 * STU-5's "Xác minh sinh viên" card (FR-5, UX-DR19): the status badge, then either the verified
 * line or the school email code. The student-card path is Story 1.13's, so only the copy names it.
 */
export function StudentVerificationCard() {
  const verification = useVerification()
  const status = verification.data?.status

  return (
    <section
      aria-labelledby="verification-heading"
      className="flex flex-col gap-4 rounded-lg border border-border bg-card p-4 md:p-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="verification-heading" className="text-h2">
          {t('screen.student.verification.heading')}
        </h2>
        {status && (
          <span className={cn('inline-flex rounded-full px-3 py-1 text-label', BADGE[status].tone)}>
            {t(BADGE[status].label)}
          </span>
        )}
      </div>
      <RegionState query={verification} skeleton={<Skeleton className="h-28 w-full" />}>
        {verification.data &&
          (verification.data.status === 'VERIFIED' ? (
            <Verified verification={verification.data} />
          ) : (
            <SchoolEmailCode verification={verification.data} />
          ))}
      </RegionState>
    </section>
  )
}

function Verified({ verification }: { verification: Verification }) {
  const domain = verification.schoolEmail?.slice(verification.schoolEmail.indexOf('@')) ?? ''
  return (
    <div className="flex flex-col gap-1">
      {verification.verifiedUntil && (
        <p className="text-body">
          {t('screen.student.verification.verifiedUntil', { date: formatCalendarDate(verification.verifiedUntil) })}
        </p>
      )}
      {verification.verifiedAt && (
        <p className="text-body-sm text-muted-foreground">
          {t('screen.student.verification.verifiedBy', { domain, date: formatDate(verification.verifiedAt) })}
        </p>
      )}
    </div>
  )
}

function SchoolEmailCode({ verification }: { verification: Verification }) {
  const pending = verification.pendingCode
  const [address, setAddress] = useState(pending?.email ?? verification.schoolEmail ?? '')
  const [entry, setEntry] = useState('')
  const [emailError, setEmailError] = useState<string>()
  const [codeError, setCodeError] = useState<string>()
  const [wrong, setWrong] = useState(false)
  const send = useSendSchoolEmailCode()
  const confirm = useConfirmSchoolEmailCode()
  const toasts = useToasts()
  const { offline } = usePollHealth()

  const exhausted = pending !== undefined && pending.attemptsLeft === 0
  const expired = pending?.expired === true
  // What the code entry shows: the server's latest refusal, else what the view says about the code.
  const entryMessage = exhausted
    ? t('reason.identity.TOO_MANY_ATTEMPTS')
    : expired
      ? t('reason.identity.CODE_EXPIRED')
      : wrong && pending
        ? t('screen.student.verification.attemptsLeft', { n: pending.attemptsLeft })
        : codeError

  function sendCode(event?: FormEvent) {
    event?.preventDefault()
    const result = email.safeParse(address)
    if (!result.success) {
      setEmailError(fieldMessage(result.error.issues[0]?.message ?? 'email'))
      return
    }
    setEmailError(undefined)
    send.mutate(result.data, {
      onSuccess: (view) => {
        setEntry('')
        setCodeError(undefined)
        setWrong(false)
        toasts.success(t('screen.student.verification.codeSent', { email: view.pendingCode?.email ?? result.data }))
      },
      onError: (error) => setEmailError(verificationMessage(error)),
    })
  }

  function confirmCode(event: FormEvent) {
    event.preventDefault()
    const result = code.safeParse(entry)
    if (!result.success) {
      setWrong(false)
      setCodeError(fieldMessage('format'))
      return
    }
    confirm.mutate(result.data, {
      onSuccess: () => toasts.success(t('screen.student.verification.done')),
      onError: (error) => {
        const reason = identityReason(error)
        setWrong(reason === 'WRONG_CODE')
        setCodeError(reason === 'WRONG_CODE' ? undefined : verificationMessage(error))
      },
    })
  }

  return (
    <div className="flex flex-col gap-4">
      {verification.status === 'EXPIRED' && verification.verifiedUntil && (
        <p className="text-body-sm">
          {t('screen.student.verification.expiredUntil', { date: formatCalendarDate(verification.verifiedUntil) })}
        </p>
      )}
      <p className="text-body-sm text-muted-foreground">{t('screen.student.verification.intro')}</p>
      <form noValidate onSubmit={pending ? confirmCode : sendCode} className="flex flex-col gap-4">
        <div className="grid gap-4 md:grid-cols-2">
          <FormField
            id="schoolEmail"
            label={t('screen.student.verification.email')}
            helper={
              pending
                ? t('screen.student.verification.sentAt', { time: formatTime(pending.sentAt) })
                : t('screen.student.verification.emailHelper')
            }
            error={emailError}
          >
            <input
              {...fieldProps(
                'schoolEmail',
                emailError,
                pending
                  ? t('screen.student.verification.sentAt', { time: formatTime(pending.sentAt) })
                  : t('screen.student.verification.emailHelper'),
              )}
              type="email"
              autoComplete="email"
              className={inputClass}
              value={address}
              maxLength={254}
              onChange={(event) => setAddress(event.target.value)}
              onKeyDown={(event) => {
                // While a code waits, Enter here sends a new code; Enter in the code field verifies.
                if (event.key === 'Enter' && pending) {
                  event.preventDefault()
                  sendCode()
                }
              }}
            />
          </FormField>
          {pending && (
            <FormField id="schoolEmailCode" label={t('screen.student.verification.code')} error={entryMessage}>
              <input
                {...fieldProps('schoolEmailCode', entryMessage)}
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                className={cn(inputClass, 'font-mono tracking-[0.3em]')}
                value={entry}
                disabled={exhausted || expired}
                onChange={(event) => setEntry(event.target.value.replace(/[^0-9]/g, ''))}
              />
            </FormField>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {pending ? (
            <>
              <Button type="submit" disabled={confirm.isPending || offline || exhausted || expired}>
                {confirm.isPending ? t('screen.student.verification.confirming') : t('screen.student.verification.confirm')}
              </Button>
              <Button type="button" variant="ghost" disabled={send.isPending || offline} onClick={() => sendCode()}>
                {send.isPending ? t('screen.student.verification.sending') : t('screen.student.verification.resend')}
              </Button>
            </>
          ) : (
            <Button type="submit" disabled={send.isPending || offline}>
              {send.isPending ? t('screen.student.verification.sending') : t('screen.student.verification.send')}
            </Button>
          )}
        </div>
      </form>
    </div>
  )
}
