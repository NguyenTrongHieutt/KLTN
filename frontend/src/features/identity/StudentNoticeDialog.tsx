import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { STUDENT_NOTICE_VERSION } from './verificationApi'

/**
 * N-26 (FR-5, FR-80, NFR-9, UX-DR94), shown before the student email form on ACC-2: what vibefix keeps of a
 * student email verification, that the declared name and major lock, and what the public profile shows, with
 * the notice's version. Going ahead opens the form; the backend records the version with "Gửi mã".
 */
export function StudentNoticeDialog({
  open,
  onOpenChange,
  onAccept,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onAccept: () => void
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100vh-96px)] w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg border-t-4 border-primary bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2">{t('notice.student.title')}</Dialog.Title>
            <Dialog.Close
              aria-label={t('notice.student.cancel')}
              className="rounded-md p-1 text-muted-foreground hover:bg-muted"
            >
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          <Dialog.Description render={<div />} className="flex flex-col gap-2 text-body-sm">
            <p>{t('notice.student.body')}</p>
            <p>{t('notice.student.kept')}</p>
            <p>{t('notice.student.locked')}</p>
            <p>{t('notice.student.public')}</p>
          </Dialog.Description>
          <p className="text-caption text-muted-foreground">
            {t('notice.student.versionLine', { version: STUDENT_NOTICE_VERSION })}
          </p>
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{t('notice.student.cancel')}</Dialog.Close>
            <Button
              type="button"
              onClick={() => {
                onOpenChange(false)
                onAccept()
              }}
            >
              {t('notice.student.button')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
