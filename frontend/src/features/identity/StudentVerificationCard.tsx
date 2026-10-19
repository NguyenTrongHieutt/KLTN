import { Tabs } from '@base-ui/react/tabs'
import { TriangleAlert } from 'lucide-react'
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
import { StudentCardUpload } from './StudentCardUpload'
import { identityReason, verificationMessage } from './verificationMessages'

const email = z.string().trim().min(1, 'required').max(254, 'tooLong').pipe(z.email('email'))

const code = z.string().trim().regex(/^[0-9]{6}$/, 'format')

const BADGE: Record<Verification['status'], { label: MessageKey; tone: string }> = {
  UNVERIFIED: { label: 'screen.student.verification.unverified', tone: 'bg-warning-soft text-warning' },
  VERIFIED: { label: 'screen.student.verification.verified', tone: 'bg-success-soft text-success' },
  EXPIRED: { label: 'screen.student.verification.expired', tone: 'bg-warning-soft text-warning' },
}

/** While the Student is not verified, a card waiting or rejected names the status instead. */
const CARD_BADGE: Partial<Record<NonNullable<Verification['studentCard']>['status'], { label: MessageKey; tone: string }>> = {
  PENDING: { label: 'screen.student.verification.cardPendingBadge', tone: 'bg-primary-soft text-primary' },
  REJECTED: { label: 'screen.student.verification.cardRejectedBadge', tone: 'bg-destructive-soft text-destructive' },
}

function badgeOf(verification: Verification) {
  const card = verification.studentCard && CARD_BADGE[verification.studentCard.status]
  return verification.status !== 'VERIFIED' && card ? card : BADGE[verification.status]
}

/**
 * STU-5's "Xác minh sinh viên" card (FR-5, UX-DR19): the status badge, then either the verified
 * line, with the two ways again once renewal opens 14 days before the expiry, or the two ways: the
 * school email code and the student card, as tabs. An expired verification adds the alert above it
 * (UX-DR27); a card waiting or rejected opens on its tab.
 */
export function StudentVerificationCard() {
  const verification = useVerification()
  const data = verification.data
  const badge = data && badgeOf(data)
  // The tab the Student chose; until then, the card's tab while a card waits or was rejected.
  const [chosen, setChosen] = useState<Way>()
  const way: Way = chosen ?? (data?.studentCard ? 'card' : 'email')

  function renew() {
    setChosen('email')
    // After the email tab has rendered; focusing scrolls the field into view as well.
    window.setTimeout(() => document.getElementById('schoolEmail')?.focus())
  }

  return (
    <>
      {data?.status === 'EXPIRED' && data.verifiedUntil && <ExpiredAlert verifiedUntil={data.verifiedUntil} onRenew={renew} />}
      <section
        aria-labelledby="verification-heading"
        className="flex flex-col gap-4 rounded-lg border border-border bg-card p-4 md:p-6"
      >
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="verification-heading" className="text-h2">
            {t('screen.student.verification.heading')}
          </h2>
          {badge && (
            <span className={cn('inline-flex rounded-full px-3 py-1 text-label', badge.tone)}>{t(badge.label)}</span>
          )}
        </div>
        <RegionState query={verification} skeleton={<Skeleton className="h-28 w-full" />}>
          {data &&
            (data.status === 'VERIFIED' ? (
              <>
                <Verified verification={data} />
                {data.renewalOpen && data.verifiedUntil && (
                  <>
                    <p className="rounded-md border-l-4 border-warning bg-warning-soft p-3 text-body-sm">
                      {t('screen.student.verification.renewalOpen', { date: formatCalendarDate(data.verifiedUntil) })}
                    </p>
                    <Ways verification={data} way={way} onWay={setChosen} />
                  </>
                )}
              </>
            ) : (
              <Ways verification={data} way={way} onWay={setChosen} />
            ))}
        </RegionState>
      </section>
    </>
  )
}

/**
 * STU-5's alert once the verification expired (FR-5, UX-DR27): what still works, and "Gia hạn",
 * which takes the keyboard to the school email field.
 */
function ExpiredAlert({ verifiedUntil, onRenew }: { verifiedUntil: string; onRenew: () => void }) {
  return (
    <div role="status" className="flex flex-wrap items-center gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <TriangleAlert aria-hidden="true" className="size-6 shrink-0 text-warning" />
      <div className="flex min-w-0 grow flex-col gap-0.5">
        <p className="text-label font-semibold">
          {t('screen.student.verification.expiredTitle', { date: formatCalendarDate(verifiedUntil).slice(0, 5) })}
        </p>
        <p className="text-body-sm">{t('screen.student.verification.expiredBody')}</p>
      </div>
      <Button type="button" size="compact" onClick={onRenew}>
        {t('screen.student.verification.renew')}
      </Button>
    </div>
  )
}

type Way = 'email' | 'card'

/** The two ways to verify, as tabs ("Cách xác minh"); a card waiting or rejected opens its tab. */
function Ways({ verification, way, onWay }: { verification: Verification; way: Way; onWay: (way: Way) => void }) {
  const tabClass =
    'min-h-control-compact border-b-2 border-transparent px-3 text-label text-muted-foreground data-active:border-primary data-active:text-foreground focus-visible:outline-2 focus-visible:outline-ring'
  return (
    <Tabs.Root value={way} onValueChange={(next) => onWay(next as Way)} className="flex flex-col gap-4">
      <Tabs.List aria-label={t('screen.student.verification.methods')} className="flex gap-2 border-b border-border">
        <Tabs.Tab value="email" className={tabClass}>
          {t('screen.student.verification.tabEmail')}
        </Tabs.Tab>
        <Tabs.Tab value="card" className={tabClass}>
          {t('screen.student.verification.tabCard')}
        </Tabs.Tab>
      </Tabs.List>
      <Tabs.Panel value="email">
        <SchoolEmailCode verification={verification} />
      </Tabs.Panel>
      <Tabs.Panel value="card">
        <StudentCardUpload verification={verification} onUseEmail={() => onWay('email')} />
      </Tabs.Panel>
    </Tabs.Root>
  )
}

function Verified({ verification }: { verification: Verification }) {
  const domain = verification.schoolEmail?.slice(verification.schoolEmail.indexOf('@')) ?? ''
  const byCard = verification.method === 'STUDENT_CARD'
  return (
    <div className="flex flex-col gap-1">
      {verification.verifiedUntil && (
        <p className="text-body">
          {t('screen.student.verification.verifiedUntil', { date: formatCalendarDate(verification.verifiedUntil) })}
        </p>
      )}
      {verification.verifiedAt && (
        <p className="text-body-sm text-muted-foreground">
          {byCard
            ? t('screen.student.verification.verifiedByCard', { date: formatDate(verification.verifiedAt) })
            : t('screen.student.verification.verifiedBy', { domain, date: formatDate(verification.verifiedAt) })}
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
          {verification.method === 'STUDENT_CARD'
            ? t('screen.student.verification.expiredUntilCard', { date: formatCalendarDate(verification.verifiedUntil) })
            : t('screen.student.verification.expiredUntil', {
                email: verification.schoolEmail ?? '',
                date: formatCalendarDate(verification.verifiedUntil),
              })}
        </p>
      )}
      {verification.status !== 'VERIFIED' && (
        <p className="text-body-sm text-muted-foreground">{t('screen.student.verification.intro')}</p>
      )}
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
