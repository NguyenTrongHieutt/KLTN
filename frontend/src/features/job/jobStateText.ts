import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDayMonth } from '@/lib/dates'
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

/** What a Milestone's status text needs besides its state (EXPERIENCE.md "Job and Milestone status text"). */
export interface MilestoneContext {
  /** Criteria done through an approval, from `work.criteriaDone` (AD-33). */
  done: number
  total: number
  /** The first Planned Milestone: the next one the Customer funds. */
  nextToFund: boolean
  dueOn?: string | null
  /** Work Items waiting for the Mentor's review, shown to the Mentor. */
  inReview?: number
}

/**
 * The single state-text mapping for Milestone states (UX-DR19): the Customer's words, or the Mentor's
 * and the Crew's; `{done}/{total}` never comes from anywhere but `work.criteriaDone`.
 */
export function milestoneStateText(state: string, audience: JobAudience, context: MilestoneContext): string {
  const counts = { done: context.done, total: context.total }
  if (audience === 'customer') {
    switch (state) {
      case 'PLANNED':
        return t(context.nextToFund ? 'screen.milestoneState.customer.unpaid' : 'screen.milestoneState.notStarted')
      case 'FUNDED':
        return t('screen.milestoneState.customer.funded', counts)
      case 'REVISION_REQUESTED':
        return t('screen.milestoneState.customer.revision', counts)
      case 'SUBMITTED':
        return t('screen.milestoneState.customer.submitted')
      default:
        return t(`screen.milestoneState.${state}` as MessageKey)
    }
  }
  switch (state) {
    case 'PLANNED':
      return t(context.nextToFund ? 'screen.milestoneState.team.unpaid' : 'screen.milestoneState.notStarted')
    case 'FUNDED': {
      const base = context.dueOn
        ? t('screen.milestoneState.team.fundedDue', { date: formatCalendarDayMonth(context.dueOn) })
        : t('screen.milestoneState.team.funded')
      return context.inReview ? `${base} · ${t('screen.milestoneState.team.inReview', { n: context.inReview })}` : base
    }
    case 'REVISION_REQUESTED':
      return t('screen.milestoneState.team.revision')
    case 'SUBMITTED':
      return t('screen.milestoneState.team.submitted')
    default:
      return t(`screen.milestoneState.${state}` as MessageKey)
  }
}

/** The tone of a Milestone's badge: waiting on the viewer, in progress, done, or neutral. */
export function milestoneTone(state: string, audience: JobAudience, nextToFund: boolean): string {
  if ((state === 'PLANNED' && nextToFund && audience === 'customer') || (state === 'SUBMITTED' && audience === 'customer')) {
    return 'bg-warning-soft text-warning'
  }
  if (state === 'REVISION_REQUESTED' && audience === 'mentor') {
    return 'bg-warning-soft text-warning'
  }
  if (state === 'ACCEPTED') {
    return 'bg-success-soft text-success'
  }
  if (state === 'FUNDED' || state === 'SUBMITTED' || state === 'REVISION_REQUESTED') {
    return 'bg-primary-soft text-primary'
  }
  return 'bg-muted text-muted-foreground'
}
