import { cn } from '@/lib/utils'

/**
 * A placeholder block shaped like the content it stands for (DESIGN.md, Skeleton): `bg-muted`, and a
 * pulse unless the user asked for reduced motion.
 */
export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn('rounded-md bg-muted motion-safe:animate-pulse', className)} />
}
