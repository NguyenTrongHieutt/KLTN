import { Dialog } from '@base-ui/react/dialog'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

const SIZES = {
  sm: 'w-[min(480px,calc(100vw-32px))]',
  md: 'w-[min(520px,calc(100vw-32px))]',
  lg: 'w-[min(560px,calc(100vw-32px))]',
} as const

const TONES = {
  none: '',
  primary: 'border-t-4 border-primary',
  warning: 'border-t-4 border-warning',
  danger: 'border-t-4 border-destructive',
} as const

/**
 * DESIGN.md Dialog: a centered `{colors.popover}` panel, `{rounded.lg}`, the small floating shadow, at most 80% of
 * the screen's height with its own scroll, in one of three widths. `tone` adds a 4 px top bar for a dialog that
 * carries a status (a refusal, a warning). It fades and grows in through the motion layer of index.css. A dialog
 * that opens over a Sheet passes a higher `z-*` in `className`.
 */
export function DialogPopup({
  size = 'md',
  tone = 'none',
  className,
  ...props
}: ComponentProps<typeof Dialog.Popup> & { size?: keyof typeof SIZES; tone?: keyof typeof TONES; className?: string }) {
  return (
    <Dialog.Popup
      className={cn(
        'fixed top-[10dvh] left-1/2 z-50 flex max-h-[80dvh] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md',
        SIZES[size],
        TONES[tone],
        className,
      )}
      {...props}
    />
  )
}

/** The dimmed layer behind a dialog; it fades with the dialog. */
export function DialogBackdrop({ className, ...props }: ComponentProps<typeof Dialog.Backdrop> & { className?: string }) {
  return <Dialog.Backdrop className={cn('fixed inset-0 z-40 bg-foreground/50', className)} {...props} />
}
