import type { ReactNode } from 'react'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { isNotFound } from '@/lib/problemMessage'
import { useJobRole, useMyJobs, youAreText, type JobRoleView } from './jobRoleApi'

type Variant = (view: JobRoleView) => ReactNode

/**
 * A Job screen's variant by the caller's role in that Job (AD-20, AD-43, UX-DR87, Story 20.6), never by the
 * account: the Customer's, the Mentor's, or the Crew's. A Job where the caller holds no role, or a role this screen
 * has no variant for, shows the not-found screen, as the API does.
 */
export function ByJobRole({
  jobId,
  customer,
  mentor,
  crew,
}: {
  jobId: string | undefined
  customer?: Variant
  mentor?: Variant
  crew?: Variant
}) {
  const role = useJobRole(jobId)
  if (jobId === undefined || (role.isError && isNotFound(role.error))) {
    return <NotFoundScreen />
  }
  if (!role.data) {
    return (
      <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
        <RegionState query={role} skeleton={<Skeleton className="h-96 w-full" />}>
          {null}
        </RegionState>
      </div>
    )
  }
  const variant = { CUSTOMER: customer, MENTOR: mentor, CREW: crew }[role.data.role]
  return variant ? <>{variant(role.data)}</> : <NotFoundScreen />
}

/**
 * JOB-H's read-only line (UX-DR87): "Bạn là: Khách hàng / Người hướng dẫn / Thành viên nhóm", with "· đã rời" for
 * a member who left before the Job ended. The Job's state comes from the screen, or else from "Công việc"
 * (`/my-jobs`). Nothing while the role loads.
 */
export function JobRoleLine({ jobId, state }: { jobId: string | undefined; state?: string }) {
  const role = useJobRole(jobId)
  const former = role.data?.crewKind === 'FORMER'
  const mine = useMyJobs(former && state === undefined)
  if (!role.data) {
    return null
  }
  const known = state ?? mine.data?.find((job) => job.jobId === jobId)?.state
  return <p className="text-caption font-semibold text-foreground">{youAreText(role.data, known)}</p>
}
