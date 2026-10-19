import { TriangleAlert } from 'lucide-react'
import { Link } from 'react-router'
import { t } from '@/i18n/t'
import type { MentorCapacity } from './jobPostApi'

/**
 * Why the Mentor cannot apply right now (FR-13, UX-DR27), on MEN-2 and MEN-3: no free Student, with
 * the way to invite one, or the Job limit reached. Browsing stays open either way.
 */
export function CapacityBanners({ capacity }: { capacity: MentorCapacity | undefined }) {
  if (!capacity) {
    return null
  }
  return (
    <>
      {capacity.reasons.includes('NO_FREE_STUDENT') && (
        <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <div className="flex flex-col gap-0.5">
            <p className="text-label font-semibold">{t('screen.jobPost.noFreeStudent')}</p>
            <p className="text-body-sm">
              {t(capacity.teamSize === 0 ? 'screen.jobPost.noFreeStudentEmpty' : 'screen.jobPost.noFreeStudentBusy')}{' '}
              <Link to="/nhom" className="text-primary underline-offset-4 hover:underline">
                {t('screen.jobPost.inviteStudents')}
              </Link>
            </p>
          </div>
        </div>
      )}
      {capacity.reasons.includes('MENTOR_JOB_LIMIT') && (
        <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <div className="flex flex-col gap-0.5">
            <p className="text-label font-semibold">{t('screen.jobPost.jobLimit', { n: capacity.jobLimit })}</p>
            <p className="text-body-sm">{t('screen.jobPost.jobLimitBody')}</p>
          </div>
        </div>
      )}
    </>
  )
}
