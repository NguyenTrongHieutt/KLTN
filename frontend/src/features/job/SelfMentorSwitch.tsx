import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { DialogBackdrop, DialogPopup } from '@/components/ui/dialog'
import { useSession } from '@/features/identity/useSession'
import { useSwitchToSelfMentored } from '@/features/matching/applicationApi'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import type { Job } from './jobApi'

/** The version of notice N-34 the dialog shows, which the switch names (FR-85). */
const NOTICE_VERSION = t('screen.job.form.selfMentored.noticeVersion')

/**
 * "Chuyển sang tự hướng dẫn" (FR-85, UX-DR121, J12) on JOB-1 while the Job is Open and in CUS-8's header: the Customer
 * of an Open Job nobody bids on becomes its Mentor and recruits the Crew directly. Hidden while the mode is off
 * (`features.selfMentoredJobs`) and on a Job that is not Open. While a bid is open the button is disabled and the reason
 * stands beside it, since a disabled button says nothing by itself. The dialog shows N-34 with what the switch adds (the
 * open invitations lapse, and it is one-way) and its checkbox; the switch is sent at the Job version the screen loaded.
 *
 * @param openBids the Job's open bids as the page read them; `undefined` while they are unknown, when the server decides
 */
export function SelfMentorSwitch({ job, openBids }: { job: Job; openBids: number | undefined }) {
  const ids = useId()
  const session = useSession().data
  const [open, setOpen] = useState(false)
  const [understood, setUnderstood] = useState(false)
  const [unread, setUnread] = useState(false)
  const checkbox = useRef<HTMLInputElement>(null)
  const change = useSwitchToSelfMentored(job.id)
  const toasts = useToasts()
  const navigate = useNavigate()
  if (session?.selfMentoredJobs !== true || job.state !== 'OPEN') {
    return null
  }
  const blocked = openBids !== undefined && openBids > 0
  const show = (next: boolean) => {
    setOpen(next)
    if (!next) {
      setUnderstood(false)
      setUnread(false)
    }
  }
  const confirm = () => {
    if (!understood) {
      setUnread(true)
      checkbox.current?.focus()
      return
    }
    change.mutate(
      { version: job.version, selfMentoredNoticeVersion: NOTICE_VERSION },
      {
        onSuccess: () => {
          show(false)
          toasts.success(t('screen.job.detail.selfMentorSwitched'))
          void navigate(`/cong-viec/${job.id}`)
        },
        onError: (error) => {
          show(false)
          toasts.error(problemMessage(error, { module: 'job', action: t('screen.job.detail.selfMentorSwitchAction') }).text)
        },
      },
    )
  }
  return (
    <div className="flex flex-col items-start gap-1 sm:items-end">
      <Button
        type="button"
        variant="secondary"
        disabled={blocked}
        aria-describedby={blocked ? `${ids}-blocked` : undefined}
        onClick={() => show(true)}
      >
        {t('screen.job.detail.selfMentorSwitch')}
      </Button>
      {blocked && (
        <p id={`${ids}-blocked`} className="max-w-xs text-caption text-muted-foreground sm:text-right">
          {t('screen.job.detail.selfMentorSwitchBlocked')}
        </p>
      )}
      <Dialog.Root open={open} onOpenChange={show}>
        <Dialog.Portal>
          <DialogBackdrop />
          <DialogPopup>
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.job.form.selfMentored.noticeTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.shared.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description render={<div />} className="flex flex-col gap-2 text-body-sm">
              <p>{t('screen.job.form.selfMentored.notice')}</p>
              <p className="font-semibold">{t('screen.job.detail.selfMentorSwitchExtra')}</p>
            </Dialog.Description>
            <div className="flex flex-col gap-1 text-body-sm">
              <label className="flex items-start gap-2">
                <input
                  type="checkbox"
                  className="mt-1"
                  ref={checkbox}
                  checked={understood}
                  aria-invalid={unread || undefined}
                  aria-describedby={unread ? `${ids}-unread` : undefined}
                  onChange={(event) => {
                    setUnderstood(event.target.checked)
                    setUnread(false)
                  }}
                />
                <span>{t('screen.job.form.selfMentored.understood')}</span>
              </label>
              {unread && (
                <p id={`${ids}-unread`} role="alert" className="text-destructive">
                  {t('screen.job.detail.selfMentorSwitchUnread')}
                </p>
              )}
            </div>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>
                {t('screen.job.detail.selfMentorSwitchBack')}
              </Dialog.Close>
              <Button type="button" onClick={confirm} disabled={change.isPending}>
                {t(change.isPending ? 'screen.job.detail.selfMentorSwitching' : 'screen.job.detail.selfMentorSwitchConfirm')}
              </Button>
            </div>
          </DialogPopup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}
