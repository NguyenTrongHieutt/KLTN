import { uploadMessage } from '@/features/files/uploadRules'
import { roleLabel, type MyJob, type RoleInJob } from '@/features/job/jobRoleApi'
import { t, type MessageKey } from '@/i18n/t'
import vi from '@/i18n/vi.json'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import type { CrewApplication } from './crewApplicationApi'

/** The refusals of a Crew Application that FND-2 words itself (FR-81, SG-58), each in its own plain sentence. */
const OWN_WORDS = [
  'ALREADY_APPLIED',
  'APPLICANT_IS_CONTRIBUTOR',
  'CONTRIBUTORS_UNKNOWN',
  'CONFLICT_OF_INTEREST',
  'PROOF_LIMIT',
  'PROOF_FILE_UNKNOWN',
]

const MATCHING_REASONS = (vi as { reason: { matching: Record<string, string> } }).reason.matching

/** The `VALIDATION` reasons that say themselves in the bid's words, which an application shares (AD-21). */
const SHARED_VALIDATION = ['LINK_INVALID', 'PUBLIC_CODE_NOT_ACKNOWLEDGED']

/**
 * An application's state as MEN-3, FND-2 and CUS-7 show it to its applicant (UX-DR103): sent with its lapse date, not
 * picked, withdrawn, or lapsed with why.
 */
export function crewApplicationStateText(application: Pick<CrewApplication, 'status' | 'endReason' | 'lapsesAt'>): string {
  if (application.status === 'PENDING') {
    return t('screen.crewApply.state.PENDING', { date: formatDayMonth(application.lapsesAt) })
  }
  return t(`screen.crewApply.state.${application.endReason ?? application.status}` as MessageKey)
}

/** What a refused Crew Application needs to say itself: the user's role in the Job, and their running Crew Job. */
export interface CrewRefusalContext {
  role?: RoleInJob
  busyJob?: string
}

/**
 * A refused Crew Application in plain words (FR-81, UX-DR95): the role already held (`OWN_JOB` for the Customer,
 * `ROLE_IN_JOB_TAKEN` otherwise), left before (`LEFT_THIS_JOB`), busy on another running Job (`CREW_MEMBER_BUSY`, naming
 * it), recruitment closed or full, a contributor, a conflict of interest with no reason, and the daily limit with when
 * to try again.
 */
export function crewRefusal(error: unknown, context: CrewRefusalContext = {}): string {
  if (!(error instanceof ApiError)) {
    return problemMessage(error, { module: 'matching' }).text
  }
  if (error.status === 429) {
    const at = new Date(Date.now() + (error.retryAfter ?? 0) * 1000).toISOString()
    return t('screen.crewApply.refusal.tooMany', { time: formatDayMonthTime(at) })
  }
  const reason = error.problem.reason
  switch (reason) {
    case 'ROLE_TAKEN':
      return context.role && context.role !== 'CUSTOMER'
        ? t('reason.matching.ROLE_IN_JOB_TAKEN', { role: roleLabel(context.role) })
        : t('reason.matching.ROLE_TAKEN')
    case 'ON_ANOTHER_CREW':
      return context.busyJob
        ? t('reason.matching.ON_ANOTHER_CREW', { job: context.busyJob })
        : t('screen.crewApply.refusal.busyElsewhere')
    case 'CREW_FULL':
      return t('reason.matching.CREW_FULL', { n: (error.problem as { n?: number }).n ?? 1 })
    case 'CREW_REJOIN_REFUSED':
    case 'RECRUITMENT_CLOSED':
      return t(`reason.matching.${reason}` as MessageKey)
    default:
      if (reason && OWN_WORDS.includes(reason)) {
        return t(`screen.crewApply.refusal.${reason}` as MessageKey)
      }
      if (reason && SHARED_VALIDATION.includes(reason)) {
        return t(`reason.matching.${reason}` as MessageKey)
      }
      return problemMessage(error, { module: 'matching' }).text
  }
}

/** The Job where the user holds a Crew row not released, other than this one: "Bạn đang tham gia {job}" (X8). */
export function runningCrewJob(jobs: MyJob[] | undefined, jobId: string): MyJob | undefined {
  return jobs?.find(
    (job) => job.jobId !== jobId && job.role === 'CREW' && (job.crewKind === 'ACTIVE' || job.crewKind === 'ASSIGNED'),
  )
}

/**
 * A refused FND-2 proof upload in plain words: the application's own reasons (the role, the recruitment closed or
 * full, left before, busy elsewhere, applied already, the proof limit) as {@link crewRefusal} says them, else the shared
 * upload text (NFR-3), which also says the upload quota's `RATE_LIMITED`.
 */
export function crewUploadRefusal(error: unknown, context: CrewRefusalContext = {}): string {
  const reason = error instanceof ApiError ? error.problem.reason : undefined
  const own = reason !== undefined && (reason in MATCHING_REASONS || OWN_WORDS.includes(reason))
  return error instanceof ApiError && error.status !== 429 && own ? crewRefusal(error, context) : uploadMessage(error)
}
