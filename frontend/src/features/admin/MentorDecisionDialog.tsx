import { Dialog } from '@base-ui/react/dialog'
import { useRef, useState, type FormEvent } from 'react'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { verificationMessage } from '@/features/identity/verificationMessages'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { fieldMessage } from '@/lib/formErrors'
import { cn } from '@/lib/utils'
import { useMentorDecision } from './adminApi'

const REAPPLY_DAYS = 30

/** The given name the dialogs speak of: the last word of a Vietnamese full name. */
function givenName(name: string): string {
  return name.trim().split(/\s+/).at(-1) ?? name
}

/**
 * ADM-13's decision dialogs (FR-74, SG-10, UX-DR42): what follows the decision, the reason a
 * rejection needs, and the authenticator code in the same dialog. A wrong code keeps it open with
 * the attempts left; the fifth ends the session, as every step-up does.
 */
export function MentorDecisionDialog({
  decision,
  applicantId,
  name,
  hasDocument,
  onClose,
}: {
  decision?: 'approve' | 'reject'
  applicantId: string
  name: string
  hasDocument: boolean
  onClose: () => void
}) {
  return (
    <Dialog.Root open={decision !== undefined} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100dvh-96px)] w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          {decision && (
            <DecisionForm
              key={decision}
              decision={decision}
              applicantId={applicantId}
              name={name}
              hasDocument={hasDocument}
              onDone={onClose}
            />
          )}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function DecisionForm({
  decision,
  applicantId,
  name,
  hasDocument,
  onDone,
}: {
  decision: 'approve' | 'reject'
  applicantId: string
  name: string
  hasDocument: boolean
  onDone: () => void
}) {
  const [reason, setReason] = useState('')
  const [code, setCode] = useState('')
  const [errors, setErrors] = useState<{ reason?: string; code?: string }>({})
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const codeRef = useRef<HTMLInputElement>(null)
  const decide = useMentorDecision()
  const toasts = useToasts()
  const approve = decision === 'approve'
  const given = givenName(name)
  // A preview of the day the server sets; the server's own `reapplyAfter` is what MEN-5 shows.
  const [reapplyDate] = useState(() =>
    formatDayMonth(new Date(Date.now() + REAPPLY_DAYS * 24 * 60 * 60 * 1000).toISOString()),
  )
  const effects = approve
    ? [
        t('screen.admin.mentors.approve1', { name: given }),
        t('screen.admin.mentors.approve2', { name: given }),
        t('screen.admin.mentors.approve3', { name: given }),
      ]
    : [
        t('screen.admin.mentors.reject1'),
        t('screen.admin.mentors.reject2', { date: reapplyDate }),
        ...(hasDocument ? [t('screen.admin.mentors.reject3')] : []),
      ]

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: { reason?: string; code?: string } = {}
    const text = reason.trim()
    if (!approve && text.length === 0) {
      next.reason = fieldMessage('required')
    } else if (!approve && text.length > 500) {
      next.reason = fieldMessage('tooLong')
    }
    if (!/^[0-9]{6}$/.test(code)) {
      next.code = fieldMessage('format')
    }
    setErrors(next)
    if (next.reason) {
      reasonRef.current?.focus()
      return
    }
    if (next.code) {
      codeRef.current?.focus()
      return
    }
    decide.mutate(
      { applicantId, reason: approve ? undefined : text, stepUpCode: code },
      {
        onSuccess: () => {
          toasts.success(t(approve ? 'screen.admin.mentors.approvedToast' : 'screen.admin.mentors.rejectedToast', { name }))
          onDone()
        },
        onError: (refusal) => {
          setCode('')
          const left = refusal instanceof ApiError ? refusal.problem.attemptsLeft : undefined
          setErrors({
            code:
              refusal instanceof ApiError && refusal.problem.reason === 'STEP_UP_FAILED' && left !== undefined
                ? t('screen.admin.stepUp.attemptsLeft', { n: left })
                : verificationMessage(refusal),
          })
          codeRef.current?.focus()
        },
      },
    )
  }

  return (
    <>
      <Dialog.Title className="text-h2 break-words">
        {t(approve ? 'screen.admin.mentors.approveTitle' : 'screen.admin.mentors.rejectTitle', { name })}
      </Dialog.Title>
      <form noValidate onSubmit={submit} className="flex flex-col gap-4">
        {!approve && (
          <FormField id="mentorRejectReason" label={t('screen.admin.mentors.reason')} error={errors.reason}>
            <textarea
              {...fieldProps('mentorRejectReason', errors.reason)}
              ref={reasonRef}
              className={cn(inputClass, 'min-h-24')}
              maxLength={500}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </FormField>
        )}
        <section aria-labelledby="mentor-effects-heading" className="flex flex-col gap-1.5 rounded-md bg-muted p-4">
          <h3 id="mentor-effects-heading" className="text-label font-semibold">
            {t('screen.admin.mentors.whatHappens')}
          </h3>
          <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
            {effects.map((effect) => (
              <li key={effect}>{effect}</li>
            ))}
          </ul>
        </section>
        <FormField
          id="mentorStepUpCode"
          label={t('screen.admin.mentors.code')}
          helper={t('screen.admin.mentors.codeHint')}
          error={errors.code}
        >
          <input
            {...fieldProps('mentorStepUpCode', errors.code, t('screen.admin.mentors.codeHint'))}
            ref={codeRef}
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            className={cn(inputClass, 'max-w-[200px] font-mono tracking-[0.3em]')}
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/[^0-9]/g, ''))}
          />
        </FormField>
        <div className="flex flex-wrap justify-between gap-3">
          <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.mentors.back')}</Dialog.Close>
          <Button type="submit" variant={approve ? 'primary' : 'destructive'} disabled={decide.isPending}>
            {decide.isPending
              ? t('screen.admin.mentors.sending')
              : t(approve ? 'screen.admin.mentors.confirmApprove' : 'screen.admin.mentors.confirmReject')}
          </Button>
        </div>
      </form>
    </>
  )
}
