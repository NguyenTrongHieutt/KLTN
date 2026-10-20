import { Menu } from '@base-ui/react/menu'
import { ChevronDown } from 'lucide-react'
import { buttonVariants } from '@/components/ui/button-variants'
import { t, type MessageKey } from '@/i18n/t'
import { movesFor, useMoveWorkItem } from './moves'
import type { Board, WorkItemCard } from './workApi'

const itemClass =
  'flex min-h-tap-min cursor-pointer flex-col items-start justify-center rounded-md px-3 py-1.5 text-body-sm data-disabled:cursor-not-allowed data-disabled:opacity-60 data-highlighted:bg-muted'

/** "Chuyển sang…" on a card or in the detail; nothing when the caller has no move. */
export function MoveMenu({ board, item, jobId }: { board: Board; item: WorkItemCard; jobId: string }) {
  const moves = movesFor(board, item)
  const move = useMoveWorkItem(jobId)
  if (moves.length === 0) {
    return null
  }
  return (
    <Menu.Root>
      <Menu.Trigger
        aria-label={t('screen.board.move.label', { key: item.key })}
        className={buttonVariants({ variant: 'ghost', size: 'compact' })}
      >
        {t('screen.board.move.trigger')}
        <ChevronDown aria-hidden="true" className="size-4" />
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner align="start" sideOffset={4} className="z-[60]">
          <Menu.Popup className="min-w-52 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md">
            {moves.map((option) => (
              <Menu.Item
                key={option.to}
                disabled={option.reason !== undefined || move.isPending}
                className={itemClass}
                onClick={() => move.mutate({ item, to: option.to })}
              >
                <span>{t(`screen.board.status.${option.to}` as MessageKey)}</span>
                {option.reason && <span className="text-caption text-muted-foreground">{option.reason}</span>}
              </Menu.Item>
            ))}
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}
