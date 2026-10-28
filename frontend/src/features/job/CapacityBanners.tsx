import { TriangleAlert } from 'lucide-react'
import { t } from '@/i18n/t'
import type { MentorCapacity } from './jobPostApi'

/**
 * Why the user cannot bid right now (FR-13, UX-DR27), in MEN-3's bid action area: the Mentor Job limit reached.
 * Browsing stays open. The free-Student banner and its Team link were retired with Teams (Story 21.1).
 */
export function CapacityBanners({ capacity }: { capacity: MentorCapacity | undefined }) {
  if (!capacity || !capacity.reasons.includes('MENTOR_JOB_LIMIT')) {
    return null
  }
  return (
    <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <div className="flex flex-col gap-0.5">
        <p className="text-label font-semibold">{t('screen.jobPost.jobLimit', { n: capacity.jobLimit })}</p>
        <p className="text-body-sm">{t('screen.jobPost.jobLimitBody')}</p>
      </div>
    </div>
  )
}
