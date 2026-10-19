import type { MentorJob } from '@/features/matching/mentorJobApi'
import { t, type MessageKey } from '@/i18n/t'
import type { Job } from './jobApi'

function types(jobTypes: string[]): string {
  return jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(', ')
}

/** "{Product} · {types} · {Mentor}": a Job screen's meta line for the Customer. */
export function customerMeta(job: Job): string {
  return [job.productName, types(job.jobTypes), job.mentor?.displayName].filter(Boolean).join(' · ')
}

/** "{Product} · {types}": a Job screen's meta line for the Mentor. */
export function mentorMeta(job: MentorJob): string {
  return `${job.productName} · ${types(job.jobTypes)}`
}
