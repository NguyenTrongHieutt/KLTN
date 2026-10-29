import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useNavigate } from 'react-router'
import { useToasts } from '@/components/toasts'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { useJob, type Job } from '@/features/job/jobApi'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { useJobApplications, useSelectApplication, type JobApplication } from './applicationApi'

/** "Chọn {mentor}?" lists what will happen before J3 (UX-DR41), from CUS-8, CUS-12 and CUS-13 alike. */
export function ChooseDialog({
  job,
  application,
  others,
  onClose,
  onStale,
}: {
  job: Job
  application: JobApplication
  others: number
  onClose: () => void
  onStale: () => void
}) {
  const select = useSelectApplication(job.id)
  const toasts = useToasts()
  const navigate = useNavigate()
  const mentor = application.mentor.displayName
  const confirm = () =>
    select.mutate(
      { applicationId: application.id, version: job.version },
      {
        onSuccess: () => {
          toasts.success(t('screen.matching.choose.done', { mentor }))
          void navigate(`/cong-viec/${job.id}`)
        },
        onError: (error) => {
          if (error instanceof ApiError && error.problem.code === 'STALE') {
            onStale()
            return
          }
          onClose()
          toasts.error(problemMessage(error, { module: 'job', action: t('screen.matching.choose.action') }).text)
        },
      },
    )
  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2 break-words">{t('screen.matching.choose.title', { mentor })}</Dialog.Title>
            <Dialog.Close aria-label={t('screen.product.finding.close')} className="rounded-md p-1 text-muted-foreground hover:bg-muted">
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          <div className="flex flex-col gap-1.5">
            <p className="text-label font-semibold">{t('screen.matching.choose.what')}</p>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
              <li>{t('screen.matching.choose.nda', { mentor })}</li>
              {others > 0 && <li>{t('screen.matching.choose.hold', { n: others })}</li>}
              <li>{t('screen.matching.choose.closed')}</li>
              <li>{t('screen.matching.choose.money')}</li>
            </ul>
          </div>
          <div className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
            <p>
              {formatVnd(application.priceVnd)}{' '}
              <TestTag />
            </p>
            <p>{t('screen.matching.days', { n: application.timelineDays })}</p>
          </div>
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{t('screen.matching.choose.back')}</Dialog.Close>
            <Button type="button" onClick={confirm} disabled={select.isPending}>
              {t('screen.matching.choose.confirm', { mentor })}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** The Stale dialog (UX-DR26): the decision was not sent; reload and decide again, never re-send. */
export function StaleDialog({ open, onClose, jobId }: { open: boolean; onClose: () => void; jobId: string }) {
  const job = useJob(jobId)
  const applications = useJobApplications(jobId)
  const reload = () => {
    void job.refetch()
    void applications.refetch()
    onClose()
  }
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('error.STALE')}</Dialog.Title>
          <Dialog.Description className="text-body-sm">{t('screen.matching.stale.body')}</Dialog.Description>
          <div className="flex justify-end">
            <Button type="button" onClick={reload}>
              {t('screen.matching.stale.reload')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
