import { Dialog } from '@base-ui/react/dialog'
import { Tabs } from '@base-ui/react/tabs'
import { BadgeCheck, TriangleAlert, X } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { z } from 'zod'
import { usePollHealth } from '@/app/usePollHealth'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDate, formatCalendarDayMonth, formatMonthYear, formatTime } from '@/lib/dates'
import { fieldMessage, serverFieldErrors } from '@/lib/formErrors'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { StudentCardUpload } from './StudentCardUpload'
import { StudentNoticeDialog } from './StudentNoticeDialog'
import {
  useConfirmSchoolEmailCode,
  useSendSchoolEmailCode,
  useUnlinkStudentVerification,
  useVerification,
  VERIFICATION_QUERY_KEY,
  type SchoolEmailSend,
  type Verification,
} from './verificationApi'
import { identityReason, verificationMessage } from './verificationMessages'

const email = z.string().trim().min(1, 'required').max(254, 'tooLong').pipe(z.email('email'))

const declared = z.string().trim().min(1, 'required').max(100, 'tooLong')

const code = z.string().trim().regex(/^[0-9]{6}$/, 'format')

/** A send refused because N-26 changed since it was shown: the notice is shown again. */
function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.problem.code === 'STALE'
}

type Way = 'email' | 'card'

/** The row's status chip: verified, verified with renewal open, or not (lapsed included). */
function statusOf(verification: Verification): { label: MessageKey; tone: string } {
  if (verification.status === 'VERIFIED') {
    return verification.renewalOpen
      ? { label: 'screen.verification.student.renewalDue', tone: 'bg-warning-soft text-warning' }
      : { label: 'screen.verification.student.verified', tone: 'bg-success-soft text-success' }
  }
  if (verification.studentCard?.status === 'PENDING') {
    return { label: 'screen.student.verification.cardPendingBadge', tone: 'bg-primary-soft text-primary' }
  }
  if (verification.studentCard?.status === 'REJECTED') {
    return { label: 'screen.student.verification.cardRejectedBadge', tone: 'bg-destructive-soft text-destructive' }
  }
  return { label: 'screen.verification.student.unverified', tone: 'bg-muted text-foreground' }
}

/**
 * ACC-2's student email row (FR-5, FR-80, AD-42, UX-DR93), for every person: N-26 first, then the form
 * (school email, full name, major, the last two "Sẽ được khóa sau khi xác minh"), then the code step,
 * which names the school and never the address, with the attempts left and "Gửi mã mới". Verified, it
 * shows the badge "Sinh viên đã xác minh" with the school, the month, and "Có hiệu lực đến", the name and
 * major marked "Khai khi xác minh", and "Bỏ xác minh". From 14 days before the expiry it reads "Sắp hết
 * hạn" with "Gia hạn", which asks for the address only; once expired it reads "Chưa xác minh" under the
 * expired alert. A Student keeps the student-card tab until Story 19.7.
 */
export function StudentEmailRow({ student }: { student: boolean }) {
  const verification = useVerification()
  const data = verification.data
  const status = data && statusOf(data)
  return (
    <section
      aria-labelledby="student-email-heading"
      className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="student-email-heading" className="text-h3">
          {t('screen.verification.student.name')}
        </h2>
        <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold text-muted-foreground">
          {t('screen.verification.student.optional')}
        </span>
        {status && (
          <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', status.tone)}>
            {t(status.label)}
          </span>
        )}
      </div>
      <RegionState query={verification} skeleton={<Skeleton className="h-28 w-full" />}>
        {data && <StudentEmailBody verification={data} student={student} />}
      </RegionState>
    </section>
  )
}

function StudentEmailBody({ verification, student }: { verification: Verification; student: boolean }) {
  const verified = verification.status === 'VERIFIED'
  const renewal = verified && verification.renewalOpen
  const mayVerify = !verified || renewal
  // N-26 is shown before the form unless it was accepted in this visit, so what is recorded was shown.
  const [notice, setNotice] = useState(false)
  const [accepted, setAccepted] = useState(false)
  const [editing, setEditing] = useState(false)
  // What was sent this visit, for "Gửi mã mới": vibefix keeps no address, so a reload asks for it again.
  const [last, setLast] = useState<SchoolEmailSend>()
  const [chosen, setChosen] = useState<Way>()
  const way: Way = chosen ?? (verification.studentCard ? 'card' : 'email')

  function start() {
    setChosen('email')
    if (accepted) {
      setEditing(true)
    } else {
      setNotice(true)
    }
  }

  /** The backend holds another N-26 version (`STALE`): the notice is read and accepted again. */
  function noticeChanged() {
    setAccepted(false)
    setNotice(true)
  }

  // The email way's step: the code waiting, or the form after N-26; neither until the person starts.
  const step =
    verification.pendingCode && !editing ? (
      <CodeStep verification={verification} last={last} onSent={setLast} onEdit={start} onStale={noticeChanged} />
    ) : editing ? (
      <SendForm
        verification={verification}
        renewal={renewal}
        onSent={(sent) => {
          setLast(sent)
          setEditing(false)
        }}
        onStale={noticeChanged}
      />
    ) : null
  // A renewal shows its step only once started; a person not verified always sees a way forward.
  const showWays = mayVerify && (step !== null || !verified)
  const intro = verification.status === 'UNVERIFIED' || student ? <Intro onStart={start} /> : null
  const emailWay = step ?? intro

  return (
    <div className="flex flex-col gap-4">
      {verification.status === 'EXPIRED' && verification.verifiedUntil && step === null && (
        <ExpiredAlert verifiedUntil={verification.verifiedUntil} onVerifyAgain={start} />
      )}
      {verified && <VerifiedFacts verification={verification} />}
      {renewal && step === null && verification.verifiedUntil && (
        <div className="flex flex-wrap items-center gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
          <p className="min-w-0 grow text-body-sm">
            {t('screen.verification.student.renewal', { date: formatCalendarDate(verification.verifiedUntil) })}
          </p>
          <Button type="button" size="compact" onClick={start}>
            {t('screen.verification.student.renew')}
          </Button>
        </div>
      )}
      {showWays &&
        (student ? (
          <Ways
            way={way}
            onWay={setChosen}
            email={emailWay}
            card={<StudentCardUpload verification={verification} onUseEmail={start} />}
          />
        ) : (
          emailWay
        ))}
      {verified && <Unlink />}
      <StudentNoticeDialog
        open={notice}
        onOpenChange={setNotice}
        onAccept={() => {
          setAccepted(true)
          setEditing(true)
        }}
      />
    </div>
  )
}

function Intro({ onStart }: { onStart: () => void }) {
  return (
    <div className="flex flex-col gap-3">
      <p className="text-body-sm">{t('screen.verification.student.why')}</p>
      <Button type="button" className="self-start" onClick={onStart}>
        {t('screen.verification.student.start')}
      </Button>
    </div>
  )
}

/** A Student's two ways, as tabs ("Cách xác minh"), until Story 19.7 removes the card. */
function Ways({
  way,
  onWay,
  email,
  card,
}: {
  way: Way
  onWay: (way: Way) => void
  email: ReactNode
  card: ReactNode
}) {
  const tabClass =
    'min-h-control-compact border-b-2 border-transparent px-3 text-label text-muted-foreground data-active:border-primary data-active:text-foreground focus-visible:outline-2 focus-visible:outline-ring'
  return (
    <Tabs.Root value={way} onValueChange={(next) => onWay(next as Way)} className="flex flex-col gap-4">
      <Tabs.List aria-label={t('screen.verification.student.methods')} className="flex gap-2 border-b border-border">
        <Tabs.Tab value="email" className={tabClass}>
          {t('screen.verification.student.tabEmail')}
        </Tabs.Tab>
        <Tabs.Tab value="card" className={tabClass}>
          {t('screen.verification.student.tabCard')}
        </Tabs.Tab>
      </Tabs.List>
      <Tabs.Panel value="email">{email}</Tabs.Panel>
      <Tabs.Panel value="card">{card}</Tabs.Panel>
    </Tabs.Root>
  )
}

/** The badge's facts: the school, how and when, until when, and the declared name and major. */
function VerifiedFacts({ verification }: { verification: Verification }) {
  return (
    <div className="flex flex-col gap-3">
      <p className="flex items-start gap-2 text-body-sm font-semibold break-words">
        <BadgeCheck aria-hidden="true" className="size-4 shrink-0 translate-y-0.5 text-success" />
        {t('screen.verification.student.verified')}
      </p>
      <dl className="grid gap-x-4 gap-y-2 text-body-sm md:grid-cols-[10rem_1fr]">
        {verification.school && <Fact label={t('screen.verification.student.school')}>{verification.school}</Fact>}
        {verification.verifiedAt && (
          <Fact label={t('screen.verification.student.source')}>
            {t(
              verification.method === 'STUDENT_CARD'
                ? 'screen.verification.student.sourceCard'
                : 'screen.verification.student.sourceEmail',
              { month: formatMonthYear(verification.verifiedAt) },
            )}
          </Fact>
        )}
        {verification.verifiedUntil && (
          <Fact label={t('screen.verification.student.validUntil')}>
            {formatCalendarDate(verification.verifiedUntil)}
          </Fact>
        )}
        {verification.name && (
          <Fact label={t('screen.verification.student.fullName')}>
            <Declared value={verification.name} />
          </Fact>
        )}
        {verification.major && (
          <Fact label={t('screen.verification.student.major')}>
            <Declared value={verification.major} />
          </Fact>
        )}
      </dl>
    </div>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </>
  )
}

/** A detail declared at verification, locked while it lasts: "Khai khi xác minh". */
function Declared({ value }: { value: string }) {
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1">
      <span className="break-words">{value}</span>
      <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold text-muted-foreground">
        {t('screen.verification.student.declared')}
      </span>
    </span>
  )
}

/** Once the badge expired: it is gone, nothing else changes, and "Xác minh lại" starts again. */
function ExpiredAlert({ verifiedUntil, onVerifyAgain }: { verifiedUntil: string; onVerifyAgain: () => void }) {
  return (
    <div
      role="status"
      className="flex flex-wrap items-center gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4"
    >
      <TriangleAlert aria-hidden="true" className="size-6 shrink-0 text-warning" />
      <div className="flex min-w-0 grow flex-col gap-0.5">
        <p className="text-label font-semibold">
          {t('screen.verification.student.expiredTitle', { date: formatCalendarDayMonth(verifiedUntil) })}
        </p>
        <p className="text-body-sm">{t('screen.verification.student.expiredBody')}</p>
      </div>
      <Button type="button" size="compact" onClick={onVerifyAgain}>
        {t('screen.verification.student.verifyAgain')}
      </Button>
    </div>
  )
}

/**
 * The form after N-26: the school email, and on a first verification the full name and the major. A renewal
 * asks for the address only, since vibefix keeps none, and keeps the locked name and major.
 */
function SendForm({
  verification,
  renewal,
  onSent,
  onStale,
}: {
  verification: Verification
  renewal: boolean
  onSent: (sent: SchoolEmailSend) => void
  onStale: () => void
}) {
  const askName = !(renewal && verification.name)
  const askMajor = !(renewal && verification.major)
  const [values, setValues] = useState({ email: '', name: '', major: '' })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const send = useSendSchoolEmailCode()
  const toasts = useToasts()
  const queryClient = useQueryClient()
  const { offline } = usePollHealth()
  const emailField = useRef<HTMLInputElement>(null)
  // The form opens on the person's own action (N-26, "Gia hạn", "Đổi email"), so the keyboard goes there.
  useEffect(() => emailField.current?.focus(), [])

  function submit(event: FormEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    const address = email.safeParse(values.email)
    if (!address.success) {
      found.email = fieldMessage(address.error.issues[0]?.message ?? 'email')
    }
    const name = declared.safeParse(values.name)
    if (askName && !name.success) {
      found.name = fieldMessage(name.error.issues[0]?.message ?? 'required')
    }
    const major = declared.safeParse(values.major)
    if (askMajor && !major.success) {
      found.major = fieldMessage(major.error.issues[0]?.message ?? 'required')
    }
    setErrors(found)
    if (Object.keys(found).length > 0 || !address.success) {
      return
    }
    const sent: SchoolEmailSend = {
      email: address.data,
      ...(askName && name.success ? { name: name.data } : {}),
      ...(askMajor && major.success ? { major: major.data } : {}),
    }
    send.mutate(sent, {
      onSuccess: (view) => {
        toasts.success(t('screen.verification.student.codeSent', { school: view.pendingCode?.school ?? '' }))
        onSent(sent)
      },
      onError: (error) => {
        if (isStale(error)) {
          onStale()
          return
        }
        const fields = serverFieldErrors(error)
        const shown = ['email', ...(askName ? ['name'] : []), ...(askMajor ? ['major'] : [])]
        if (Object.keys(fields).some((field) => !shown.includes(field))) {
          // The row changed since it was read (a renewal whose verification lapsed asks for the details again).
          setErrors({ email: verificationMessage(error) })
          void queryClient.invalidateQueries({ queryKey: VERIFICATION_QUERY_KEY })
          return
        }
        setErrors(Object.keys(fields).length > 0 ? fields : { email: verificationMessage(error) })
      },
    })
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      <FormField
        id="schoolEmail"
        label={t('screen.verification.student.email')}
        helper={t(renewal ? 'screen.verification.student.renewHelper' : 'screen.verification.student.emailHelper')}
        error={errors.email}
      >
        <input
          {...fieldProps(
            'schoolEmail',
            errors.email,
            t(renewal ? 'screen.verification.student.renewHelper' : 'screen.verification.student.emailHelper'),
          )}
          ref={emailField}
          type="email"
          autoComplete="email"
          className={inputClass}
          value={values.email}
          maxLength={254}
          onChange={(event) => setValues({ ...values, email: event.target.value })}
        />
      </FormField>
      {(askName || askMajor) && (
        <div className="grid gap-4 md:grid-cols-2">
          {askName && (
            <FormField
              id="studentName"
              label={t('screen.verification.student.fullName')}
              helper={t('screen.verification.student.lockedHelper')}
              error={errors.name}
            >
              <input
                {...fieldProps('studentName', errors.name, t('screen.verification.student.lockedHelper'))}
                type="text"
                autoComplete="name"
                className={inputClass}
                value={values.name}
                maxLength={100}
                onChange={(event) => setValues({ ...values, name: event.target.value })}
              />
            </FormField>
          )}
          {askMajor && (
            <FormField
              id="studentMajor"
              label={t('screen.verification.student.major')}
              helper={t('screen.verification.student.lockedHelper')}
              error={errors.major}
            >
              <input
                {...fieldProps('studentMajor', errors.major, t('screen.verification.student.lockedHelper'))}
                type="text"
                className={inputClass}
                value={values.major}
                maxLength={100}
                onChange={(event) => setValues({ ...values, major: event.target.value })}
              />
            </FormField>
          )}
        </div>
      )}
      <Button type="submit" className="self-start" disabled={send.isPending || offline}>
        {send.isPending ? t('screen.verification.student.sending') : t('screen.verification.student.send')}
      </Button>
    </form>
  )
}

/**
 * The code step: the school, never the address; the 6-digit field with the attempts left; "Gửi mã mới",
 * which sends again what this visit sent, or asks for the address again after a reload; and "Đổi email".
 */
function CodeStep({
  verification,
  last,
  onSent,
  onEdit,
  onStale,
}: {
  verification: Verification
  last?: SchoolEmailSend
  onSent: (sent: SchoolEmailSend) => void
  onEdit: () => void
  onStale: () => void
}) {
  const pending = verification.pendingCode!
  const [entry, setEntry] = useState('')
  const [codeError, setCodeError] = useState<string>()
  const [wrong, setWrong] = useState(false)
  const send = useSendSchoolEmailCode()
  const confirm = useConfirmSchoolEmailCode()
  const toasts = useToasts()
  const { offline } = usePollHealth()

  const exhausted = pending.attemptsLeft === 0
  const expired = pending.expired
  // What the code entry shows: the server's latest refusal (a failed "Gửi mã mới" included), else what the
  // view says about the code.
  const entryMessage =
    codeError ??
    (exhausted
      ? t('reason.identity.TOO_MANY_ATTEMPTS')
      : expired
        ? t('reason.identity.CODE_EXPIRED')
        : wrong
          ? t('screen.verification.student.attemptsLeft', { n: pending.attemptsLeft })
          : undefined)

  function resend() {
    if (!last) {
      onEdit()
      return
    }
    send.mutate(last, {
      onSuccess: (view) => {
        setEntry('')
        setCodeError(undefined)
        setWrong(false)
        toasts.success(t('screen.verification.student.codeSent', { school: view.pendingCode?.school ?? '' }))
        onSent(last)
      },
      onError: (error) => {
        setWrong(false)
        if (isStale(error)) {
          onStale()
          return
        }
        setCodeError(verificationMessage(error))
      },
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
      onSuccess: (view) =>
        toasts.success(
          t('screen.verification.student.done', {
            school: view.school ?? '',
            date: view.verifiedUntil ? formatCalendarDate(view.verifiedUntil) : '',
          }),
        ),
      onError: (error) => {
        const reason = identityReason(error)
        setWrong(reason === 'WRONG_CODE')
        setCodeError(reason === 'WRONG_CODE' ? undefined : verificationMessage(error))
        // A refusal that spends the code (the email verifies another account, the school left the list, no code)
        // ends this step once the row is read again, so it is told as a toast too.
        if (!reason || !['WRONG_CODE', 'CODE_EXPIRED', 'TOO_MANY_ATTEMPTS'].includes(reason)) {
          toasts.error(verificationMessage(error))
        }
      },
    })
  }

  const hint = t('screen.verification.student.attemptsHint', { n: pending.attemptsLeft })
  return (
    <form noValidate onSubmit={confirmCode} className="flex flex-col gap-4">
      <div role="status" className="flex flex-col gap-0.5">
        <p className="text-body-sm break-words">
          {t('screen.verification.student.codeStep', { school: pending.school })}
        </p>
        <p className="text-caption text-muted-foreground">
          {t('screen.verification.student.sentAt', { time: formatTime(pending.sentAt) })}
        </p>
      </div>
      <FormField id="schoolEmailCode" label={t('screen.verification.student.code')} helper={hint} error={entryMessage}>
        <input
          {...fieldProps('schoolEmailCode', entryMessage, hint)}
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          className={cn(inputClass, 'max-w-48 font-mono tracking-[0.3em]')}
          value={entry}
          disabled={exhausted || expired}
          onChange={(event) => setEntry(event.target.value.replace(/[^0-9]/g, ''))}
        />
      </FormField>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={confirm.isPending || offline || exhausted || expired}>
          {confirm.isPending ? t('screen.verification.student.confirming') : t('screen.verification.student.confirm')}
        </Button>
        <Button type="button" variant="ghost" disabled={send.isPending || offline} onClick={resend}>
          {send.isPending ? t('screen.verification.student.sending') : t('screen.verification.student.resend')}
        </Button>
        <Button type="button" variant="ghost" onClick={onEdit}>
          {t('screen.verification.student.otherEmail')}
        </Button>
      </div>
    </form>
  )
}

/** "Bỏ xác minh", after a Confirmation dialog saying the encrypted signal stays, as N-26 stated (FR-80). */
function Unlink() {
  const [open, setOpen] = useState(false)
  const unlink = useUnlinkStudentVerification()
  const toasts = useToasts()
  const confirm = () =>
    unlink.mutate(undefined, {
      onSuccess: () => {
        setOpen(false)
        toasts.success(t('screen.verification.student.unlinked'))
      },
      onError: (failure) => {
        setOpen(false)
        toasts.error(problemMessage(failure, { module: 'identity' }).text)
      },
    })
  return (
    <div className="flex flex-col gap-2 border-t border-border pt-3">
      <Button type="button" variant="secondary" className="w-fit" onClick={() => setOpen(true)}>
        {t('screen.verification.student.unlink')}
      </Button>
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg border-t-4 border-destructive bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.verification.student.unlinkTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.verification.student.cancel')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description className="text-body-sm">
              {t('screen.verification.student.unlinkBody')}
            </Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>
                {t('screen.verification.student.cancel')}
              </Dialog.Close>
              <Button type="button" variant="destructive" onClick={confirm} disabled={unlink.isPending}>
                {unlink.isPending
                  ? t('screen.verification.student.unlinking')
                  : t('screen.verification.student.unlinkConfirm')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}
