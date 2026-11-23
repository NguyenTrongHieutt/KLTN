import { StatusBadge } from '@/components/ui/status-badge'
import type { JobPhase } from './jobPostApi'
import { phaseText } from './phaseText'

/** The read-only Status badge of a listed Job's phase, in the in-progress tone (`status-badge-in-progress`). */
export function PhaseBadge({ phase, className }: { phase: JobPhase | undefined; className?: string }) {
  if (!phase) {
    return null
  }
  return (
    <StatusBadge tone="progress" className={className}>
      {phaseText(phase)}
    </StatusBadge>
  )
}
