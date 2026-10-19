import { t, type MessageKey } from '@/i18n/t'
import type { JobState } from './jobApi'

/** Who reads the state: the Customer, or the Mentor and the Crew (UX-DR19). */
export type JobAudience = 'customer' | 'mentor'

/**
 * The single state-text mapping for Job states (UX-DR19, EXPERIENCE.md "Job and Milestone status
 * text"): every surface says the same thing, with the Customer's words or the Mentor's. A Mentor
 * never sees a Draft, so it reads as the Customer's.
 */
export function jobStateText(state: JobState, audience: JobAudience = 'customer'): string {
  if (audience === 'mentor' && state !== 'DRAFT') {
    return t(`screen.job.mentorState${state}` as MessageKey)
  }
  return t(`screen.job.state${state}` as MessageKey)
}
