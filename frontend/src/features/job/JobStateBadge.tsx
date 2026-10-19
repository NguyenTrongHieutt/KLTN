import { cn } from '@/lib/utils'
import type { JobState } from './jobApi'
import { jobStateText, type JobAudience } from './jobStateText'

type Tone = 'waiting' | 'progress' | 'done' | 'neutral'

const TONE_CLASSES: Record<Tone, string> = {
  waiting: 'bg-warning-soft text-warning',
  progress: 'bg-primary-soft text-primary',
  done: 'bg-success-soft text-success',
  neutral: 'bg-muted text-foreground',
}

const TONES: Record<JobState, Tone> = {
  DRAFT: 'neutral',
  OPEN: 'progress',
  SELECTING: 'progress',
  AGREEMENT: 'progress',
  STARTING: 'progress',
  ACTIVE: 'progress',
  CLOSING: 'progress',
  CLOSED: 'done',
  CANCELLED: 'neutral',
}

/** The read-only Status badge of a Job's state. */
export function JobStateBadge({
  state,
  audience = 'customer',
  className,
}: {
  state: JobState
  audience?: JobAudience
  className?: string
}) {
  return (
    <span
      className={cn(
        'w-fit rounded-full px-2 py-0.5 text-caption font-semibold',
        TONE_CLASSES[audience === 'mentor' && state === 'SELECTING' ? 'waiting' : TONES[state]],
        className,
      )}
    >
      {jobStateText(state, audience)}
    </span>
  )
}
