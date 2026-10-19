import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

/**
 * The Sheet (DESIGN.md, UX-DR48): a panel from the right edge, full width on phones, for a profile
 * or a search beside the screen it came from. Closing it returns focus to what opened it.
 */
export function Sheet({
  open,
  onClose,
  title,
  description,
  footer,
  children,
}: {
  open: boolean
  onClose: () => void
  title: string
  description?: ReactNode
  footer?: ReactNode
  children: ReactNode
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[560px] flex-col bg-popover text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
            <div className="flex min-w-0 flex-col gap-1">
              <Dialog.Title className="text-h2 break-words">{title}</Dialog.Title>
              {description && (
                <Dialog.Description className="text-caption text-muted-foreground">{description}</Dialog.Description>
              )}
            </div>
            <Dialog.Close
              aria-label={t('screen.team.close')}
              className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'px-2')}
            >
              <X aria-hidden="true" />
            </Dialog.Close>
          </div>
          <div className="flex grow flex-col gap-5 overflow-y-auto px-5 py-4">{children}</div>
          {footer && <div className="flex flex-wrap items-center justify-end gap-3 border-t border-border px-5 py-3">{footer}</div>}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
