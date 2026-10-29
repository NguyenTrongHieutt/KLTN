import { cn } from '@/lib/utils'
import type { JobPhase } from './jobPostApi'
import { phaseText } from './phaseText'

/** The read-only Status badge of a listed Job's phase, in the in-progress tone (`status-badge-in-progress`). */
export function PhaseBadge({ phase, className }: { phase: JobPhase | undefined; className?: string }) {
  if (!phase) {
    return null
  }
  return (
    <span className={cn('w-fit rounded-full bg-primary-soft px-2 py-0.5 text-caption font-semibold text-primary', className)}>
      {phaseText(phase)}
    </span>
  )
}
