import { Dialog } from '@base-ui/react/dialog'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'

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
        <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2 break-words">{title}</Dialog.Title>
          <Dialog.Description className="text-body-sm break-words">{body}</Dialog.Description>
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.team.back')}</Dialog.Close>
            <Button type="button" variant={destructive ? 'destructive' : 'primary'} disabled={pending} onClick={onConfirm}>
              {confirmLabel}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
