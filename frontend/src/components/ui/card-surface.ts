/**
 * The surface of every card (the layout of 2026-11-23): `{colors.card}` on the canvas, `{rounded.lg}`, the resting
 * shadow `shadow-card` and no border; the dark theme adds a hairline border, where a shadow barely shows. <Card> is
 * built on it; use the class directly only where the element cannot be a <Card> (a link, a label, a form, details).
 */
export const cardSurface =
  'rounded-lg border border-transparent bg-card text-card-foreground shadow-card dark:border-border'

/**
 * A card that is itself a link or a button: it rises on hover and on keyboard focus, and gives a little under the
 * press (ui-ux-pro-max scale-feedback), without moving anything around it.
 */
export const cardInteractive =
  'transition-[box-shadow,scale] duration-(--duration-fast) hover:shadow-raised focus-visible:shadow-raised active:scale-[0.99]'

/** A placeholder card (an empty slot, a drop zone): a dashed outline and no shadow. Add it after `cardSurface`. */
export const cardDashed = 'border-dashed border-border-strong shadow-none dark:border-border-strong'

/**
 * A page's top as one card: its title rows and, when a tab row is the card's last child, the tabs running edge to
 * edge along the card's bottom, over a hairline, without their own bottom rule. A header with no tab row keeps the
 * card's padding all round.
 */
export const headerCard = [
  'flex flex-col gap-3 p-4 md:p-5',
  cardSurface,
  '[&>nav:last-child]:-mx-4 [&>nav:last-child]:-mb-4 [&>nav:last-child]:border-t [&>nav:last-child]:border-b-0 [&>nav:last-child]:px-2',
  'md:[&>nav:last-child]:-mx-5 md:[&>nav:last-child]:-mb-5 md:[&>nav:last-child]:px-3',
].join(' ')
