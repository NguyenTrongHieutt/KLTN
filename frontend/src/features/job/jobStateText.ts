import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDayMonth } from '@/lib/dates'
import type { JobState } from './jobApi'
import type { RoleInJob } from './jobRoleApi'

/** Who reads the state: the Customer, or the Mentor and the Crew (UX-DR19). */
export type JobAudience = 'customer' | 'mentor'

/** The words a role in the Job reads (UX-DR87): the Customer's, or the Mentor's and the Crew's. */
export function audienceOf(role: RoleInJob): JobAudience {
  return role === 'CUSTOMER' ? 'customer' : 'mentor'
}

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
  /** The first Planned Milestone: the next one the Customer funds. */
  nextToFund: boolean
  dueOn?: string | null
  /** The Revision Rounds left (FR-34). */
  roundsLeft?: number
  /** How many criteria the latest revision request named (FR-33). */
  revisionCriteria?: number | null
  /** Accepted by Auto-acceptance (FR-35). */
  autoAccepted?: boolean
  /** Cancelled with its held money refunded (FR-40). */
  refunded?: boolean
}

/**
 * The single state-text mapping for Milestone states (UX-DR19): the Customer's words, or the Mentor's and
 * the Crew's. No label counts anything inside a Milestone: the platform no longer tracks the team's tasks
 * (Story 24.2a), so the state, the due date, the Revision Rounds and the decision are all a label has.
 */
export function milestoneStateText(state: string, audience: JobAudience, context: MilestoneContext): string {
  if (audience === 'customer') {
    switch (state) {
      case 'PLANNED':
        return t(context.nextToFund ? 'screen.milestoneState.customer.unpaid' : 'screen.milestoneState.notStarted')
      case 'FUNDED':
        return t('screen.milestoneState.customer.funded')
      case 'REVISION_REQUESTED':
        return t('screen.milestoneState.customer.revision')
      case 'SUBMITTED':
        return context.roundsLeft === undefined
          ? t('screen.milestoneState.customer.submitted')
          : `${t('screen.milestoneState.customer.submitted')} · ${t('screen.milestoneState.roundsLeft', { r: context.roundsLeft })}`
      case 'ACCEPTED':
        return t(context.autoAccepted ? 'screen.milestoneState.autoAccepted' : 'screen.milestoneState.ACCEPTED')
      case 'CANCELLED':
        return t(context.refunded ? 'screen.milestoneState.cancelledRefunded' : 'screen.milestoneState.CANCELLED')
      default:
        return t(`screen.milestoneState.${state}` as MessageKey)
    }
  }
  switch (state) {
    case 'PLANNED':
      return t(context.nextToFund ? 'screen.milestoneState.team.unpaid' : 'screen.milestoneState.notStarted')
    case 'FUNDED':
      return context.dueOn
        ? t('screen.milestoneState.team.fundedDue', { date: formatCalendarDayMonth(context.dueOn) })
        : t('screen.milestoneState.team.funded')
    case 'REVISION_REQUESTED':
      return context.revisionCriteria
        ? t('screen.milestoneState.team.revisionCount', { n: context.revisionCriteria, r: context.roundsLeft ?? 0 })
        : t('screen.milestoneState.team.revision')
    case 'SUBMITTED':
      return t('screen.milestoneState.team.submitted')
    case 'ACCEPTED':
      return t(context.autoAccepted ? 'screen.milestoneState.autoAccepted' : 'screen.milestoneState.ACCEPTED')
    case 'CANCELLED':
      return t(context.refunded ? 'screen.milestoneState.cancelledRefunded' : 'screen.milestoneState.CANCELLED')
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
