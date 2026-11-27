import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

/**
 * Button styles (DESIGN.md button-primary, button-secondary, button-destructive): 44 px tall on
 * Customer screens, `compact` (36 px) on dense ones, `{rounded.lg}` (the layout of 2026-11-23), and the 2 px focus ring of the
 * Accessibility Floor. Links that look like buttons use these classes too.
 */
const variants = cva(
  'inline-flex shrink-0 items-center justify-center gap-2 rounded-lg border border-transparent text-label font-semibold text-center transition-[color,background-color,border-color,translate] duration-(--duration-fast) select-none active:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*=size-])]:size-5',
  {
    variants: {
      variant: {
        primary: 'bg-primary text-primary-foreground hover:bg-primary/90',
        secondary: 'border-border-strong bg-card text-foreground hover:border-input hover:bg-muted',
        destructive: 'bg-destructive text-destructive-foreground hover:bg-destructive/90',
        ghost: 'text-foreground hover:bg-muted',
        link: 'h-auto px-0 text-primary underline-offset-4 hover:underline',
      },
      size: {
        default: 'min-h-tap-min px-4 py-2 md:min-h-control-compact md:px-3.5 md:py-1.5',
        compact: 'min-h-control-compact px-3 py-1.5',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'default',
    },
  },
)

export type ButtonVariantProps = VariantProps<typeof variants>

/**
 * The button classes, merged: a variant's border or text color replaces the base one even where the classes are used
 * directly on a link, without `cn` (a secondary link lost its border before 2026-11-23).
 */
export function buttonVariants(props?: Parameters<typeof variants>[0]): string {
  return cn(variants(props))
}
