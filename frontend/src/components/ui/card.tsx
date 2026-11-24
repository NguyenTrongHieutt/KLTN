import { createElement, type HTMLAttributes, type ReactNode, type Ref } from 'react'
import { cn } from '@/lib/utils'
import { cardDashed, cardSurface } from './card-surface'

type CardElement = 'div' | 'section' | 'article' | 'li' | 'aside' | 'ul' | 'ol' | 'p'

/**
 * The card (the layout of 2026-11-23): `{colors.card}` on the canvas, `{rounded.lg}`, the resting shadow and no
 * border (see `cardSurface`). One padding for every card (16 px on phones, 20 px from 768 px), so cards line up
 * wherever they sit. `padding="none"` is for cards whose rows run edge to edge (lists, tables). `dashed` is the
 * placeholder card: an empty slot or a drop zone, outlined and flat.
 */
export function Card({
  as = 'div',
  padding = 'default',
  dashed = false,
  className,
  children,
  ...props
}: HTMLAttributes<HTMLElement> & {
  as?: CardElement
  padding?: 'default' | 'none'
  dashed?: boolean
  ref?: Ref<HTMLElement>
  children?: ReactNode
}) {
  return createElement(
    as,
    {
      className: cn(
        'min-w-0',
        cardSurface,
        dashed && cardDashed,
        padding === 'default' && 'p-4 md:p-5',
        className,
      ),
      ...props,
    },
    children,
  )
}

/**
 * A card's title row: the title (an `h2` by default, set in `{typography.h3}` because it sits inside a card), an
 * optional line under it, and optional trailing content (a status badge or one action) that wraps under the title
 * on narrow screens.
 */
export function CardHeader({
  title,
  description,
  aside,
  level = 2,
  id,
  className,
}: {
  title: ReactNode
  description?: ReactNode
  aside?: ReactNode
  level?: 2 | 3
  id?: string
  className?: string
}) {
  return (
    <div className={cn('flex flex-wrap items-start justify-between gap-x-4 gap-y-2', className)}>
      <div className="flex min-w-0 flex-col gap-1">
        {createElement(`h${level}`, { id, className: 'text-h3 break-words' }, title)}
        {description && <p className="text-body-sm text-muted-foreground">{description}</p>}
      </div>
      {aside && <div className="flex shrink-0 flex-wrap items-center gap-2">{aside}</div>}
    </div>
  )
}

/**
 * A titled section of a page, outside any card: an `h2` in `{typography.h2}`, an optional line under it, optional
 * actions on the right, then its content. Sections are separated by the page's own gap.
 */
export function Section({
  title,
  description,
  actions,
  id,
  className,
  children,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  id: string
  className?: string
  children: ReactNode
}) {
  return (
    <section aria-labelledby={id} className={cn('flex min-w-0 flex-col gap-3', className)}>
      <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
        <div className="flex min-w-0 flex-col gap-1">
          <h2 id={id} className="text-h2 break-words">
            {title}
          </h2>
          {description && <p className="text-body-sm text-muted-foreground">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  )
}
