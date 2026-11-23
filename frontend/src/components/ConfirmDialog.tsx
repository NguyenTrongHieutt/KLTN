import { Dialog } from '@base-ui/react/dialog'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { DialogBackdrop, DialogPopup } from '@/components/ui/dialog'

/**
 * The Confirmation dialog (DESIGN.md): the action's effect in a sentence, "Quay lại", and the
 * action itself, which waits for the server.
 */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  destructive = false,
  pending,
  onConfirm,
  onClose,
}: {
  open: boolean
  title: string
  body: string
  confirmLabel: string
  destructive?: boolean
  pending: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <DialogBackdrop className="z-[60]" />
        <DialogPopup size="sm" className="z-[70]">
          <Dialog.Title className="text-h2 break-words">{title}</Dialog.Title>
          <Dialog.Description className="text-body-sm break-words">{body}</Dialog.Description>
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.shared.back')}</Dialog.Close>
            <Button type="button" variant={destructive ? 'destructive' : 'primary'} disabled={pending} onClick={onConfirm}>
              {confirmLabel}
            </Button>
          </div>
        </DialogPopup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
