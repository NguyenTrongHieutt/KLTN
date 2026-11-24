import { cardInteractive, cardSurface } from '@/components/ui/card-surface'
import { cn } from '@/lib/utils'

/**
 * A grid of Job tiles (the layout of 2026-11-23): as many columns as fit tiles at least 17 rem wide, so the same
 * list is one column in a narrow side column and three across a wide page.
 */
export const jobGrid = 'grid gap-3 grid-cols-[repeat(auto-fill,minmax(min(100%,17rem),1fr))]'

/** One Job tile: a card that is a link, its status at the bottom so the statuses of a row line up. */
export const jobTile = cn('flex h-full flex-col gap-3 p-4 text-inherit no-underline', cardSurface, cardInteractive)
