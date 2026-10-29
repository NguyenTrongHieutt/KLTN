import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { SIGN_DAYS, usePickCrewApplication, useWithdrawPick, type ApplicantCard } from './crewApplicationApi'
import { pickRefusal, signDeadline } from './crewText'

/**
 * "Chọn" asks first (UX-DR102): "{name} có {days} ngày để ký cam kết. Trước khi họ ký, bạn có thể rút lựa chọn.", with
 * the deadline the pick would set.
 */
export function PickDialog({ jobId, application, onClose }: { jobId: string; application: ApplicantCard; onClose: () => void }) {
  const pick = usePickCrewApplication(jobId)
  const toasts = useToasts()
  const name = application.applicant.displayName
  const confirm = () =>
    pick.mutate(application.id, {
      onSuccess: (picked) => {
        onClose()
        toasts.success(
          t('screen.recruitment.picked', { name, date: picked.signDueAt ? formatDayMonthTime(picked.signDueAt) : '' }),
        )
      },
      onError: (error) => {
        onClose()
        toasts.error(pickRefusal(error, name))
      },
    })
  return (
    <PickConfirm
      title={t('screen.recruitment.pickTitle', { name })}
      body={t('screen.recruitment.pickBody', { name, days: SIGN_DAYS })}
      detail={t('screen.recruitment.pickDeadline', { date: formatDayMonthTime(signDeadline()) })}
      back={t('screen.recruitment.declineBack')}
      confirm={t('screen.recruitment.pick')}
      pending={pick.isPending}
      onClose={onClose}
      onConfirm={confirm}
    />
  )
}

/** "Rút lựa chọn" asks first (R4): the person is told, and the place returns while recruitment is on. */
export function WithdrawPickDialog({
  jobId,
  application,
  onClose,
}: {
  jobId: string
  application: ApplicantCard
  onClose: () => void
}) {
  const withdraw = useWithdrawPick(jobId)
  const toasts = useToasts()
  const name = application.applicant.displayName
  const confirm = () =>
    withdraw.mutate(application.id, {
      onSuccess: () => {
        onClose()
        toasts.success(t('screen.recruitment.pickWithdrawn', { name }))
      },
      onError: (error) => {
        onClose()
        toasts.error(
          error instanceof ApiError && error.problem.reason === 'NOT_WITHDRAWABLE'
            ? t('screen.recruitment.pickRefusal.NOT_WITHDRAWABLE', { name })
            : problemMessage(error, { module: 'matching' }).text,
        )
      },
    })
  return (
    <PickConfirm
      title={t('screen.recruitment.withdrawPickTitle', { name })}
      body={t('screen.recruitment.withdrawPickBody', { name })}
      back={t('screen.recruitment.declineBack')}
      confirm={t('screen.recruitment.withdrawPick')}
      pending={withdraw.isPending}
      destructive
      onClose={onClose}
      onConfirm={confirm}
    />
  )
}

function PickConfirm({
  title,
  body,
  detail,
  back,
  confirm,
  pending,
  destructive = false,
  onClose,
  onConfirm,
}: {
  title: string
  body: string
  detail?: string
  back: string
  confirm: string
  pending: boolean
  destructive?: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2">{title}</Dialog.Title>
            <Dialog.Close aria-label={t('screen.product.finding.close')} className="rounded-md p-1 text-muted-foreground hover:bg-muted">
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          <Dialog.Description className="text-body-sm">{body}</Dialog.Description>
          {detail && <p className="text-label font-semibold">{detail}</p>}
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{back}</Dialog.Close>
            <Button type="button" variant={destructive ? 'destructive' : 'primary'} onClick={onConfirm} disabled={pending}>
              {confirm}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
