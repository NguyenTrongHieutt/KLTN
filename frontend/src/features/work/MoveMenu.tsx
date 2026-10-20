import { Menu } from '@base-ui/react/menu'
import { ChevronDown } from 'lucide-react'
import { buttonVariants } from '@/components/ui/button-variants'
import { t, type MessageKey } from '@/i18n/t'
import { actionsFor, useMoveWorkItem, type Action } from './moves'
import type { Board, WorkItemCard } from './workApi'

const itemClass =
  'flex min-h-tap-min cursor-pointer flex-col items-start justify-center rounded-md px-3 py-1.5 text-body-sm data-disabled:cursor-not-allowed data-disabled:opacity-60 data-highlighted:bg-muted'

function label(action: Action): string {
  switch (action.kind) {
    case 'move':
      return t(`screen.board.status.${action.to}` as MessageKey)
    case 'submit':
      return t('screen.board.move.submit')
    case 'approve':
      return t('screen.board.move.approve')
    case 'return':
      return t('screen.board.move.return')
  }
}

/**
 * "Chuyển sang…" on a card or in the detail (FR-24, FR-25, UX-DR36): only the caller's actions; a Board
 * move happens at once, and "Gửi duyệt", "Duyệt", and "Trả lại" open their dialogs. Nothing shows when
 * the caller has no action.
 */
export function MoveMenu({
  board,
  item,
  jobId,
  onReview,
}: {
  board: Board
  item: WorkItemCard
  jobId: string
  /** Opens the Submit or Review dialog for this Work Item. */
  onReview: (item: WorkItemCard, action: 'submit' | 'approve' | 'return') => void
}) {
  const actions = actionsFor(board, item)
  const move = useMoveWorkItem(jobId)
  if (actions.length === 0) {
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
            {actions.map((action) => (
              <Menu.Item
                key={action.kind === 'move' ? action.to : action.kind}
                disabled={(action.kind === 'move' && action.reason !== undefined) || move.isPending}
                className={itemClass}
                onClick={() =>
                  action.kind === 'move' ? move.mutate({ item, to: action.to }) : onReview(item, action.kind)
                }
              >
                <span>{label(action)}</span>
                {action.kind === 'move' && action.reason && (
                  <span className="text-caption text-muted-foreground">{action.reason}</span>
                )}
              </Menu.Item>
            ))}
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}
