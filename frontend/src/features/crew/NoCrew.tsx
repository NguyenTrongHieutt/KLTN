import { Info, TriangleAlert } from 'lucide-react'
import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { useJobRole } from '@/features/job/jobRoleApi'
import { t } from '@/i18n/t'

/**
 * The No-Crew banner on JOB-H, so on JOB-1 and JOB-2 too (FR-82, Q7, R8, UX-DR104): for the Mentor of a Job in Starting
 * or Active with no counted member, with "Tuyển thành viên" (JOB-9). Nothing dismisses it; it goes when a member signs.
 */
export function NoCrewBanner({ jobId }: { jobId: string }) {
  const role = useJobRole(jobId).data
  if (role?.role !== 'MENTOR' || !role.noCrew) {
    return null
  }
  return (
    <div
      role="status"
      className="flex flex-wrap items-center gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm"
    >
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <p className="min-w-60 flex-1 break-words">{t('screen.crew.noCrew.mentor')}</p>
      <Link
        to={`/cong-viec/${jobId}/tuyen-thanh-vien`}
        className={buttonVariants({ variant: 'secondary', size: 'compact' })}
      >
        {t('screen.crew.mentor.recruit')}
      </Link>
    </div>
  )
}

/**
 * The Customer's alert on JOB-1 (FR-82, Q7, UX-DR104): that the Mentor carries on, in plain words and no more about the
 * Crew. Nothing dismisses it; it goes when a member signs, and it never shows before Starting.
 */
export function NoCrewAlert({ jobId, mentor }: { jobId: string; mentor: string }) {
  const role = useJobRole(jobId).data
  if (role?.role !== 'CUSTOMER' || !role.noCrew) {
    return null
  }
  return (
    <div role="status" className="flex gap-3 rounded-md border-l-4 border-info bg-info-soft p-4 text-body-sm">
      <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
      <p className="break-words">{t('screen.crew.noCrew.customer', { mentor })}</p>
    </div>
  )
}

