import { Info, TriangleAlert } from 'lucide-react'
import { Link } from 'react-router'
import type { components } from '@/api/schema'
import { buttonVariants } from '@/components/ui/button-variants'
import { useJobRole } from '@/features/job/jobRoleApi'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'

type Leaver = components['schemas']['LeaverView']

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
    <div role="status" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
      <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
      <p className="break-words">{t('screen.crew.noCrew.customer', { mentor })}</p>
    </div>
  )
}

/**
 * "{name} đã rời công việc ngày {date}" on JOB-1, JOB-2 and JOB-9, for the Mentor, while a former member's open Work
 * Items wait to be assigned again (UX-DR104): one text for a leave and a lock, so nobody can tell them apart.
 */
export function CrewLeftLines({ leavers }: { leavers: Leaver[] | undefined }) {
  if (!leavers || leavers.length === 0) {
    return null
  }
  return (
    <ul className="flex flex-col gap-1.5">
      {leavers.map((leaver) => (
        <li
          key={`${leaver.name}-${leaver.leftAt}`}
          className="rounded-md border-l-4 border-warning bg-warning-soft px-4 py-3 text-body-sm break-words"
        >
          {t('screen.crew.leftLine', { name: leaver.name, date: formatDayMonth(leaver.leftAt) })}
        </li>
      ))}
    </ul>
  )
}
