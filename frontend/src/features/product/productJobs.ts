import type { JobState, JobSummary } from '@/features/job/jobApi'
import { t } from '@/i18n/t'

/** States in which a Job no longer runs. */
export const ENDED_JOB_STATES: readonly JobState[] = ['CLOSED', 'CANCELLED']

export interface ProductJobCounts {
  running: number
  ended: number
}

/**
 * The Product's Jobs, counted from the Customer's own Job list (`GET /api/v1/jobs`), since `ProductView.jobs` is
 * not filled yet: a Draft counts as running, a Closed or Cancelled Job as ended.
 */
export function productJobCounts(jobs: readonly JobSummary[], productId: string): ProductJobCounts {
  const mine = jobs.filter((job) => job.productId === productId)
  const ended = mine.filter((job) => ENDED_JOB_STATES.includes(job.state)).length
  return { running: mine.length - ended, ended }
}

/** "Chưa có công việc", "2 công việc đang chạy", "1 công việc đã kết thúc", or "1 đang chạy · 2 đã kết thúc". */
export function productJobsText({ running, ended }: ProductJobCounts): string {
  if (running === 0 && ended === 0) {
    return t('screen.product.noJobs')
  }
  if (ended === 0) {
    return t('screen.product.jobsRunning', { n: running })
  }
  if (running === 0) {
    return t('screen.product.jobsEnded', { n: ended })
  }
  return t('screen.product.jobsBoth', { running, ended })
}
