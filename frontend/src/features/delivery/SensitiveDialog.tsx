import { Dialog } from '@base-ui/react/dialog'
import { TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'

/** What the writer chose after the FR-47 warning: check first, or store the masked text. */
export type Resolution = 'CHECK' | 'MASK'

/** One masked FR-47 match, as the warning dialog lists it. */
export interface SensitiveMatch {
  kind: string
  type: string
  masked: string
}

/**
 * FR-47 (AD-18): the masked matches, then "Che và lưu" stores the masked text and "Sửa lại" goes back. It
 * serves the handover form and the Customer's guided check alike.
 */
export function SensitiveDialog({
  matches,
  pending,
  onMask,
  onEdit,
}: {
  matches: SensitiveMatch[]
  pending: boolean
  onMask: () => void
  onEdit: () => void
}) {
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onEdit()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[80] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[90] flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('screen.handover.sensitive.title')}</Dialog.Title>
          <div className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4 text-body-sm">
            <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
            <div className="flex flex-col gap-1.5">
              <p>{t('screen.chat.warning.found')}</p>
              <ul className="flex flex-col gap-1">
                {matches.map((match, index) => (
                  <li key={`${match.masked}-${index}`} className="font-mono break-all">
                    {match.masked}
                  </li>
                ))}
              </ul>
              <Dialog.Description>{t('screen.handover.sensitive.body')}</Dialog.Description>
            </div>
          </div>
          <div className="flex flex-wrap justify-end gap-3">
            <Button type="button" variant="secondary" onClick={onEdit}>
              {t('screen.chat.warning.edit')}
            </Button>
            <Button type="button" disabled={pending} onClick={onMask}>
              {t('screen.handover.sensitive.mask')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
