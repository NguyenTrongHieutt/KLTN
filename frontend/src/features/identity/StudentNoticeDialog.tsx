import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { STUDENT_NOTICE_VERSION } from './verificationApi'
import { DialogBackdrop, DialogPopup } from '@/components/ui/dialog'

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
        <DialogBackdrop />
        <DialogPopup size="lg" tone="primary">
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
        </DialogPopup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
