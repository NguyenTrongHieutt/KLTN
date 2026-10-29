import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { useWithdrawCrewApplication, type CrewApplication } from './crewApplicationApi'
import { crewApplicationStateText } from './crewText'

/**
 * The applicant's state (UX-DR103): "Đã gửi · chờ người hướng dẫn xem · hết hạn ngày {date}" with "Xem" (FND-2) and
 * "Rút" behind a confirmation while the application is pending, wherever it shows (MEN-3 while the Job is listed or
 * after it left the listing, and FND-2); an application that ended shows only why.
 */
export function CrewApplicationState({ application, view = true }: { application: CrewApplication; view?: boolean }) {
  const [confirming, setConfirming] = useState(false)
  const withdraw = useWithdrawCrewApplication()
  const toasts = useToasts()
  const confirm = () =>
    withdraw.mutate(application.id, {
      onSuccess: () => {
        setConfirming(false)
        toasts.success(t('screen.crewApply.withdrawn'))
      },
      onError: (error) => {
        setConfirming(false)
        toasts.error(problemMessage(error, { module: 'matching' }).text)
      },
    })
  return (
    <div className="flex flex-col gap-2">
      <p role="status" className="text-label font-semibold">
        {crewApplicationStateText(application)}
      </p>
      {application.status === 'PENDING' && (
        <div className="flex flex-wrap items-center gap-3">
          {view && (
            <Link to={`/tim-viec/${application.jobId}/xin-vao-nhom`} className={buttonVariants({ variant: 'secondary' })}>
              {t('screen.crewApply.view')}
            </Link>
          )}
          <Button type="button" variant="secondary" onClick={() => setConfirming(true)}>
            {t('screen.crewApply.withdraw')}
          </Button>
        </div>
      )}
      <Dialog.Root open={confirming} onOpenChange={setConfirming}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.crewApply.withdrawTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.product.finding.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description className="text-body-sm">{t('screen.crewApply.withdrawBody')}</Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.crewApply.keep')}</Dialog.Close>
              <Button type="button" onClick={confirm} disabled={withdraw.isPending}>
                {t('screen.crewApply.withdraw')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}
