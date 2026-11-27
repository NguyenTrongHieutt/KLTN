import { Dialog } from '@base-ui/react/dialog'
import { Info, TriangleAlert } from 'lucide-react'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { DialogBackdrop, DialogPopup } from '@/components/ui/dialog'
import { useCancellation, useCancellationCommand, type Cancellation } from '@/features/job/cancellation/cancellationApi'
import { useJobRole } from '@/features/job/jobRoleApi'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'

const banner = 'flex flex-wrap items-center gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm'

/**
 * The No-Crew banner on JOB-H, so on JOB-1 and JOB-2 too (FR-82, Q7, R8, UX-DR104): for the Mentor of a Job in Starting
 * or Active with no counted member, with "Tuyển thành viên" (JOB-9). Nothing dismisses it; it goes when a member signs.
 * The Customer of a Self-mentored Job recruits the Crew too, and reads the no-member banner of that mode instead.
 */
export function NoCrewBanner({ jobId }: { jobId: string }) {
  const role = useJobRole(jobId).data
  if ((role?.role !== 'MENTOR' && role?.role !== 'CUSTOMER_MENTOR') || !role.noCrew) {
    return null
  }
  if (role.role === 'CUSTOMER_MENTOR') {
    return <NoMemberBanner jobId={jobId} />
  }
  return (
    <div role="status" className={banner}>
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <p className="min-w-60 flex-1 break-words">{t('screen.crew.noCrew.mentor')}</p>
      <RecruitLink jobId={jobId} />
    </div>
  )
}

function RecruitLink({ jobId }: { jobId: string }) {
  return (
    <Link to={`/cong-viec/${jobId}/tuyen-thanh-vien`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
      {t('screen.crew.mentor.recruit')}
    </Link>
  )
}

/**
 * The no-member banner of a Self-mentored Job (FR-85, R49, UX-DR124): only a member hands over, so the Mentor's
 * "continue alone" does not apply. It reads the cancellation's own answer to know which of three things to say: with
 * no Held Milestone, recruit; with a Held Milestone nobody worked on since it was funded, recruit or cancel it alone
 * with the full refund; with one a member worked on, why it cannot be cancelled alone, and recruit or open a Dispute.
 * While that answer loads it says the first, which holds in all three.
 */
function NoMemberBanner({ jobId }: { jobId: string }) {
  const cancellation = useCancellation(jobId).data
  const [asking, setAsking] = useState(false)
  const state = cancellation?.noMember
  return (
    <div role="status" className={banner}>
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <p className="min-w-60 flex-1 break-words">
        {t(
          state === 'ALONE'
            ? 'screen.crew.noCrew.selfMentorAlone'
            : state === 'CREW_WAS_ACTIVE'
              ? 'screen.crew.noCrew.selfMentorCrewWasActive'
              : 'screen.crew.noCrew.selfMentor',
        )}
      </p>
      <RecruitLink jobId={jobId} />
      {state === 'ALONE' && cancellation?.canPropose && (
        <Button type="button" variant="destructive" size="compact" onClick={() => setAsking(true)}>
          {t('screen.crew.noCrew.cancelHeld')}
        </Button>
      )}
      {state === 'CREW_WAS_ACTIVE' && (
        <Link to={`/cong-viec/${jobId}#tranh-chap`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
          {t('screen.cancellation.openDispute')}
        </Link>
      )}
      {asking && cancellation && <CancelHeldDialog cancellation={cancellation} onClose={() => setAsking(false)} />}
    </div>
  )
}

/**
 * "Hủy giai đoạn đang giữ tiền" (R49): a Confirmation dialog that states the full refund and that the Job ends with it,
 * since a Milestone is never cancelled apart from its Job. The reason is kept with the cancellation.
 */
function CancelHeldDialog({ cancellation, onClose }: { cancellation: Cancellation; onClose: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useCancellationCommand(cancellation.jobId)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const confirm = () => {
    setError(undefined)
    command.mutate(
      { action: 'propose', scope: 'JOB', reason: reason.trim(), version: cancellation.version },
      {
        onSuccess: () => {
          toasts.success(t('screen.cancellation.confirmed'))
          onClose()
        },
        onError: (failure) =>
          setError(
            failure instanceof ApiError && failure.problem.code === 'STALE'
              ? t('screen.cancellation.stale')
              : problemMessage(failure, { module: 'job' }).text,
          ),
      },
    )
  }
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <DialogBackdrop />
        <DialogPopup size="sm">
          <Dialog.Title className="text-h2">{t('screen.crew.noCrew.cancelHeldTitle')}</Dialog.Title>
          <Dialog.Description className="flex flex-col gap-2 text-body-sm">
            <span>{t('screen.crew.noCrew.cancelHeldRefund')}</span>
            <span>{t('screen.crew.noCrew.cancelHeldEnd')}</span>
          </Dialog.Description>
          <FormField id={`${ids}-reason`} label={t('screen.cancellation.reason')}>
            <textarea
              {...fieldProps(`${ids}-reason`)}
              rows={3}
              maxLength={2000}
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
          <div className="flex flex-wrap justify-end gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{t('screen.cancellation.cancel')}</Dialog.Close>
            <Button
              type="button"
              variant="destructive"
              disabled={reason.trim() === '' || command.isPending}
              onClick={confirm}
            >
              {t('screen.crew.noCrew.cancelHeld')}
            </Button>
          </div>
        </DialogPopup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/**
 * The Customer's alert on JOB-1 (FR-82, Q7, UX-DR104): that the Mentor carries on, in plain words and no more about the
 * Crew. Nothing dismisses it; it goes when a member signs, and it never shows before Starting.
 */
export function NoCrewAlert({ jobId, mentor }: { jobId: string; mentor: string }) {
  const role = useJobRole(jobId).data
  if (role?.role !== 'CUSTOMER' || !role.noCrew) {
    return null
  }
  return (
    <div role="status" className="flex gap-3 rounded-md border-l-4 border-info bg-info-soft p-4 text-body-sm">
      <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
      <p className="break-words">{t('screen.crew.noCrew.customer', { mentor })}</p>
    </div>
  )
}
