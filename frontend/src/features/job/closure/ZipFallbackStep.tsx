import { Dialog } from '@base-ui/react/dialog'
import { Download, TriangleAlert } from 'lucide-react'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { SensitiveChangeList } from '@/features/delivery/SensitiveChangeList'
import { t, type MessageKey } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { MERGE_CODE_PARAM, useDeclineFinalMerge, type FinalMerge } from './finalMergeApi'

/**
 * N-17 on JOB-1 (FR-38, UX-DR8, UX-DR55): the ZIP Fallback replaces the merge steps. It says why the
 * merge could not happen, offers "Tải mã về (tệp ZIP)" (an attachment served to the Customer alone, which
 * may contain programs), lists the Sensitive Changes, and offers "Đăng công việc ghép mã". Nothing asks
 * the Customer to run anything. While the ZIP is built, it says so.
 */
export function ZipFallbackStep({ jobId, merge }: { jobId: string; merge: FinalMerge }) {
  const ids = useId()
  const zip = merge.zip
  if (!zip) {
    return null
  }
  if (zip.status !== 'READY' || !zip.fileId) {
    return (
      <p role="status" className="text-body-sm text-muted-foreground">
        {t('screen.closure.zip.building')}
      </p>
    )
  }
  return (
    <section aria-labelledby={`${ids}-zip`} className="flex flex-col gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <p id={`${ids}-zip`} className="flex items-start gap-2 text-body-sm">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warning" />
        {t('screen.closure.zip.lead', { reason: t(`screen.closure.zip.reason.${zip.reason}` as MessageKey) })}
      </p>
      <div className="flex flex-col gap-1">
        <a
          href={`/api/v1/files/${zip.fileId}`}
          download
          className={cn(buttonVariants(), 'w-fit no-underline')}
        >
          <Download aria-hidden="true" className="size-4" />
          {t('term.zipFallback.label')}
        </a>
        <p className="text-caption text-muted-foreground">{t('screen.closure.zip.programs')}</p>
      </div>
      <SensitiveChangeList changes={zip.sensitive} headingId={`${ids}-sensitive`} />
      <Link
        to={`/san-pham/${merge.productId}/cong-viec-moi?${MERGE_CODE_PARAM}=${jobId}`}
        className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit no-underline')}
      >
        {t('screen.closure.zip.post')}
      </Link>
    </section>
  )
}

/**
 * "Nhận mã dạng ZIP" (FR-37, FR-38): offered with "Cho phép hợp nhất" until the merge is confirmed,
 * after a Confirmation dialog saying vibefix will not merge into the repository, a request approved later
 * is no longer used, the Job ends when the ZIP is ready, and the choice cannot be undone.
 */
export function DeclineToZip({ jobId, repo }: { jobId: string; repo: string }) {
  const decline = useDeclineFinalMerge(jobId)
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string>()
  return (
    <>
      <Button type="button" variant="secondary" className="w-fit" onClick={() => setOpen(true)}>
        {t('screen.closure.zip.declineOffer')}
      </Button>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2">{t('screen.closure.zip.declineTitle')}</Dialog.Title>
            <Dialog.Description className="text-body-sm">{t('screen.closure.zip.declineBody', { repo })}</Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
                {t('screen.closure.zip.declineCancel')}
              </Button>
              <Button
                type="button"
                disabled={decline.isPending}
                onClick={() => {
                  setError(undefined)
                  decline.mutate(undefined, {
                    onSuccess: () => setOpen(false),
                    onError: (failure) => {
                      setOpen(false)
                      setError(problemMessage(failure, { module: 'repo' }).text)
                    },
                  })
                }}
              >
                {t('screen.closure.zip.declineConfirm')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  )
}
