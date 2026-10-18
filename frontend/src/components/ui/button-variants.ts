import { cva, type VariantProps } from 'class-variance-authority'

/**
 * Button styles (DESIGN.md button-primary, button-secondary, button-destructive): 44 px tall on
 * Customer screens, `compact` (36 px) on dense ones, `rounded-md`, and the 2 px focus ring of the
 * Accessibility Floor. Links that look like buttons use these classes too.
 */
export const buttonVariants = cva(
  'inline-flex shrink-0 items-center justify-center gap-2 rounded-md border border-transparent text-label font-semibold text-center transition-colors select-none disabled:pointer-events-none disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*=size-])]:size-5',
  {
    variants: {
      variant: {
        primary: 'bg-primary text-primary-foreground hover:bg-primary/90',
        secondary: 'border-input bg-card text-foreground hover:bg-muted',
        destructive: 'bg-destructive text-destructive-foreground hover:bg-destructive/90',
        ghost: 'text-foreground hover:bg-muted',
        link: 'h-auto px-0 text-primary underline-offset-4 hover:underline',
      },
      size: {
        default: 'min-h-tap-min px-4 py-2',
        compact: 'min-h-control-compact px-3 py-1.5',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'default',
    },
  },
)

export type ButtonVariantProps = VariantProps<typeof buttonVariants>
