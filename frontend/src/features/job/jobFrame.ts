import { headerCard } from '@/components/ui/card-surface'

/**
 * The top of a Job screen as one card (the layout of 2026-11-23): the breadcrumb, the title with its badges, the
 * meta line, the stage strip, and the tab row along the card's bottom (see `headerCard`).
 */
export const jobHeaderCard = headerCard

/** A Job screen's side column: from 1024 px it stays in view under the top bar while the main column scrolls. */
export const jobAside =
  'flex flex-col gap-4 lg:sticky lg:top-[calc(var(--spacing-header-height)+1rem)] lg:self-start'
