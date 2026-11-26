import type { ReactNode } from 'react'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { useJobRole, youAreText, type JobRoleView, type RoleInJob } from './jobRoleApi'
import { PhaseBadge } from './PhaseBadge'
import { Page } from '@/components/ui/page'

type Variant = (view: JobRoleView) => ReactNode

/**
 * A Job screen's variant by the caller's role in that Job (AD-20, AD-43, UX-DR87, Story 20.6), never by the
 * account: the Customer's, the Mentor's, or the Crew's. A Job where the caller holds no role, or a role this screen
 * has no variant for, shows the not-found screen, as the API does.
 * <p>
 * `customerMentor` is the variant of the Customer of a Self-mentored Job (AD-47, Story 25.2a), and every screen must
 * name it: the side AD-20 maps that screen to (the Customer's on a Customer's decision or duty, the Mentor's on
 * recruitment, the Agreement draft and the Crew Chat), or `null` where the mode has no such screen. A screen that
 * leaves it out does not build.
 */
export function ByJobRole({
  jobId,
  customer,
  mentor,
  customerMentor,
  crew,
}: {
  jobId: string | undefined
  customer?: Variant
  mentor?: Variant
  customerMentor: Variant | null
  crew?: Variant
}) {
  const role = useJobRole(jobId)
  if (jobId === undefined || (role.isError && isNotFound(role.error))) {
    return <NotFoundScreen />
  }
  if (!role.data) {
    return (
      <Page>
        <RegionState query={role} skeleton={<Skeleton className="h-96 w-full" />}>
          {null}
        </RegionState>
      </Page>
    )
  }
  const variants: Record<RoleInJob, Variant | null | undefined> = {
    CUSTOMER: customer,
    MENTOR: mentor,
    CUSTOMER_MENTOR: customerMentor,
    CREW: crew,
  }
  const variant = variants[role.data.role]
  return variant ? <>{variant(role.data)}</> : <NotFoundScreen />
}

/**
 * JOB-H's read-only line (UX-DR87): "Bạn là: Khách hàng / Người hướng dẫn / Thành viên nhóm", with "· đã rời" for
 * a member who left, also after the Job ends, and on every tab of a former member the banner that they read only their
 * own time on the Crew (AD-41). Nothing while the role loads.
 */
export function JobRoleLine({ jobId }: { jobId: string | undefined }) {
  const role = useJobRole(jobId)
  if (!role.data) {
    return null
  }
  return (
    <>
      <p className="text-caption font-semibold text-foreground">{youAreText(role.data)}</p>
      {role.data.crewKind === 'FORMER' && (
        <p role="status" className="w-full rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
          {t('screen.progress.former')}
        </p>
      )}
    </>
  )
}

/** JOB-H's phase badge beside the state's (X6), for the Mentor and the Customer: "Đang tìm nhóm thực hiện", … */
export function JobPhaseBadge({ jobId }: { jobId: string | undefined }) {
  const phase = useJobRole(jobId).data?.phase
  // While a Mentor is still sought the state badge beside it says so already ("Đang tìm người hướng dẫn"), or says
  // more ("Chờ người hướng dẫn ký cam kết bảo mật"): the phase would only repeat or contradict it (seen 2026-11-24).
  // "Đang thống nhất" likewise repeats "Đang thống nhất thỏa thuận" once the Crew is found.
  if (phase === 'FINDING_MENTOR' || phase === 'AGREEING') {
    return null
  }
  return <PhaseBadge phase={phase} />
}
