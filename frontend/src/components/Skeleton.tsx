import { cn } from '@/lib/utils'

/**
 * A placeholder block shaped like the content it stands for (DESIGN.md, Skeleton): `{colors.border}`, which shows
 * on the canvas as well as on a card (`{colors.muted}` is nearly the canvas's own colour), and a pulse unless the user
 * asked for reduced motion.
 */
export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn('rounded-md bg-border motion-safe:animate-pulse', className)} />
}
