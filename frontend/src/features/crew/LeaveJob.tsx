import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useId, useState } from 'react'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { MAX_LEFT_NOTE, useLeaveJob, type CrewMe } from './crewApi'

/**
 * "Rời công việc" on a member's JOB-1 (FR-82, UX-DR104): the Leave dialog with N-28, in the words of this Job: a Job
 * with no code has no repository to speak of, and the split sentence names the Milestones the member stays a party of,
 * or with none only their post-Job duties (R9). The optional reason follows, marked as read by the Mentor alone. It
 * takes effect at once and cannot be undone.
 */
export function LeaveJob({ me }: { me: CrewMe }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button type="button" variant="secondary" className="w-fit" onClick={() => setOpen(true)}>
        {t('screen.crew.leave.action')}
      </Button>
      {open && <LeaveDialog me={me} onClose={() => setOpen(false)} />}
    </>
  )
}

function LeaveDialog({ me, onClose }: { me: CrewMe; onClose: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const leave = useLeaveJob(me.jobId)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const confirm = () => {
    setError(undefined)
    leave.mutate(reason, {
      onSuccess: () => {
        toasts.success(t('screen.crew.leave.done'))
        onClose()
      },
      onError: (failure) => setError(problemMessage(failure, { module: 'job' }).text),
    })
  }
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100vh-96px)] w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2 break-words">{t('screen.crew.leave.title', { job: me.title })}</Dialog.Title>
            <Dialog.Close
              aria-label={t('screen.product.finding.close')}
              className="rounded-md p-1 text-muted-foreground hover:bg-muted"
            >
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          <Dialog.Description className="text-body-sm">{notice(me)}</Dialog.Description>
          <FormField
            id={`${ids}-reason`}
            label={t('screen.crew.leave.reason')}
            helper={t('screen.crew.leave.reasonHint')}
          >
            <textarea
              {...fieldProps(`${ids}-reason`, undefined, t('screen.crew.leave.reasonHint'))}
              rows={3}
              maxLength={MAX_LEFT_NOTE}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              className={inputClass}
            />
          </FormField>
          {error && (
            <p role="alert" className="text-body-sm text-destructive">
              {error}
            </p>
          )}
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{t('screen.crew.leave.back')}</Dialog.Close>
            <Button type="button" variant="destructive" onClick={confirm} disabled={leave.isPending}>
              {t('screen.crew.leave.action')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** N-28 (FR-82, R3, R9, R15). */
function notice(me: CrewMe): string {
  const sentences = [t(me.codeJob ? 'screen.crew.leave.repository' : 'screen.crew.leave.noRepository')]
  sentences.push(
    me.partyMilestones.length > 0
      ? t('screen.crew.leave.split', { n: me.partyMilestones.join(', ') })
      : t('screen.crew.leave.duties'),
  )
  sentences.push(t('screen.crew.leave.customer'))
  return sentences.join(' ')
}
