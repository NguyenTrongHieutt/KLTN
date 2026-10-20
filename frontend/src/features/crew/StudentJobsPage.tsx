import { BriefcaseBusiness, TriangleAlert, Users } from 'lucide-react'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { NDA_VERSION } from '@/features/matching/nda'
import { CommitEmailNotice, NdaText } from '@/features/matching/NdaText'
import { ConfirmDialog } from '@/features/team/ConfirmDialog'
import { hasBoard } from '@/features/work/workApi'
import { useStudentTeam } from '@/features/team/teamApi'
import { TestTag } from '@/components/TestTag'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDayMonth, formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { useConfirmCrewTerms, useCrewAssignments, useDeclineCrewTerms, type CrewAssignment } from './crewApi'

/**
 * STU-4 "Công việc của tôi" (`/cong-viec` for a Student, FR-75, AD-41, UX-DR60): an assignment waiting
 * for the Student shows the Crew assignment panel (N-19, N-14 with the NDA text, N-5, and N-23 for a
 * Code Job); a confirmed one shows as "Đang tham gia". Nothing else of the Job is reachable yet.
 */
export function StudentJobsPage() {
  const assignments = useCrewAssignments()
  const team = useStudentTeam()
  const waiting = assignments.data?.filter((assignment) => assignment.status === 'ASSIGNED') ?? []
  const joined = assignments.data?.filter((assignment) => assignment.status !== 'ASSIGNED') ?? []
  return (
    <div className="mx-auto flex max-w-[920px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.crew.student.title')}</PageHeading>
      <RegionState query={assignments} skeleton={<Skeleton className="h-96 w-full" />}>
        {assignments.data && (
          <>
            {waiting.map((assignment) => (
              <AssignmentPanel key={`${assignment.jobId}-${assignment.versionNo}`} assignment={assignment} />
            ))}
            {joined.length > 0 && (
              <section className="flex flex-col gap-2.5">
                <h2 className="text-h2">{t('screen.crew.student.activeTitle')}</h2>
                {joined.map((assignment) => (
                  <JoinedCard key={assignment.jobId} assignment={assignment} />
                ))}
              </section>
            )}
            {assignments.data.length === 0 && (
              <>
                {team.data && !team.data.team && (
                  <div className="flex flex-wrap items-center gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4">
                    <Users aria-hidden="true" className="size-5 shrink-0 text-primary" />
                    <div className="flex min-w-0 grow flex-col gap-0.5">
                      <p className="text-label font-semibold">{t('screen.crew.student.noTeam')}</p>
                      <p className="text-body-sm">{t('screen.crew.student.noTeamBody')}</p>
                    </div>
                    <Link to="/nhom" className={buttonVariants({ size: 'compact' })}>
                      {t('screen.crew.student.findMentor')}
                    </Link>
                  </div>
                )}
                <div className="flex flex-col items-center gap-2.5 rounded-lg border border-border bg-card px-6 py-10 text-center">
                  <BriefcaseBusiness aria-hidden="true" className="size-12 text-muted-foreground" />
                  <h2 className="text-h3">{t('screen.crew.student.empty')}</h2>
                  <p className="text-body-sm text-muted-foreground">{t('screen.crew.student.emptyBody')}</p>
                </div>
              </>
            )}
          </>
        )}
      </RegionState>
    </div>
  )
}

/** The Crew assignment panel: everything the Student agrees to, and nothing of the other shares. */
function AssignmentPanel({ assignment }: { assignment: CrewAssignment }) {
  const ids = useId()
  const toasts = useToasts()
  const confirm = useConfirmCrewTerms(assignment.jobId)
  const decline = useDeclineCrewTerms(assignment.jobId)
  const [acknowledged, setAcknowledged] = useState(false)
  const [understood, setUnderstood] = useState(false)
  const [declining, setDeclining] = useState(false)
  const needsNotice = assignment.codeJob && !assignment.commitEmailNoticeAcknowledged
  const hasTerms = assignment.versionNo > 0 && assignment.sharePercent != null
  const ready = hasTerms && acknowledged && (!needsNotice || understood)
  const pending = confirm.isPending || decline.isPending

  const failed = (error: unknown) =>
    toasts.error(
      error instanceof ApiError && error.problem.code === 'STALE'
        ? t('screen.crew.student.stale')
        : problemMessage(error, { module: 'job', action: t('screen.crew.student.confirm').toLowerCase() }).text,
    )
  const submit = () =>
    confirm.mutate(
      {
        crewTermsVersionNo: assignment.versionNo,
        ndaVersion: NDA_VERSION,
        publicCodeAcknowledged: acknowledged,
        commitEmailNoticeAcknowledged: understood || assignment.commitEmailNoticeAcknowledged,
      },
      { onSuccess: () => toasts.success(t('screen.crew.student.confirmed')), onError: failed },
    )
  const leave = () =>
    decline.mutate(assignment.versionNo, {
      onSuccess: () => {
        setDeclining(false)
        toasts.success(t('screen.crew.student.declined'))
      },
      onError: (error) => {
        setDeclining(false)
        failed(error)
      },
    })

  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-4 rounded-lg border-2 border-primary bg-card p-5">
      {assignment.signed && hasTerms && (
        <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <div className="flex flex-col gap-0.5">
            <p className="font-semibold">{t('screen.crew.student.changedTitle')}</p>
            <p>{t('screen.crew.student.changedBody', { n: assignment.versionNo })}</p>
          </div>
        </div>
      )}
      <div className="flex flex-col gap-1">
        <p className="text-caption text-muted-foreground">{t('screen.crew.student.kicker', { product: assignment.productName })}</p>
        <div className="flex flex-wrap items-center gap-2">
          <h2 id={`${ids}-title`} className="text-h2 break-words">
            {assignment.title}
          </h2>
          <span className="rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold text-warning">
            {t('screen.crew.student.waitingBadge')}
          </span>
        </div>
        <p className="text-body-sm">{t('screen.crew.student.mentor', { name: assignment.mentorName })}</p>
      </div>
      {assignment.scope && (
        <div className="flex flex-col gap-1">
          <p className="text-label font-semibold">{t('screen.crew.student.scope')}</p>
          <p className="text-body-sm break-words whitespace-pre-line">{assignment.scope}</p>
        </div>
      )}
      {assignment.milestones.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[520px] text-left text-body-sm">
            <thead className="text-caption text-muted-foreground">
              <tr className="border-b border-border">
                <th scope="col" className="py-2 pr-3 font-semibold">
                  {t('screen.crew.student.milestone')}
                </th>
                <th scope="col" className="py-2 pr-3 font-semibold">
                  {t('screen.crew.student.due')}
                </th>
                <th scope="col" className="py-2 pr-3 font-semibold">
                  {t('screen.crew.student.price')}
                </th>
                <th scope="col" className="py-2 font-semibold">
                  {t('screen.crew.student.yourPart')}
                </th>
              </tr>
            </thead>
            <tbody>
              {assignment.milestones.map((milestone, m) => (
                <tr key={`${m}-${milestone.title}`} className="border-b border-border last:border-b-0">
                  <td className="py-2 pr-3 break-words">{t('screen.crew.student.milestoneRow', { n: m + 1, title: milestone.title })}</td>
                  <td className="py-2 pr-3">{milestone.dueOn ? formatCalendarDayMonth(milestone.dueOn) : '—'}</td>
                  <td className="py-2 pr-3">
                    {milestone.priceVnd != null ? (
                      <>
                        {formatVnd(milestone.priceVnd)} <TestTag />
                      </>
                    ) : (
                      '—'
                    )}
                  </td>
                  <td className="py-2">
                    {milestone.yourPartVnd != null ? (
                      <>
                        <span className="font-semibold">{formatVnd(milestone.yourPartVnd)}</span> <TestTag />
                      </>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {hasTerms ? (
        <div className="flex flex-col gap-1 rounded-md border border-primary bg-primary-soft p-4">
          <p className="text-h3">{t('screen.crew.student.share', { share: assignment.sharePercent ?? 0 })}</p>
          <p className="text-caption text-muted-foreground">
            {t('screen.crew.student.version', {
              n: assignment.versionNo,
              date: assignment.versionSentAt ? formatDayMonth(assignment.versionSentAt) : '',
            })}
          </p>
        </div>
      ) : (
        <p className="rounded-md bg-muted p-4 text-body-sm">{t('screen.crew.student.noTerms')}</p>
      )}
      <p className="text-body-sm">{t('screen.crew.student.n19')}</p>
      <div className="flex flex-col gap-2">
        <p className="text-label font-semibold">{t('screen.nda.title', { version: NDA_VERSION })}</p>
        {assignment.signed ? <p className="text-body-sm text-muted-foreground">{t('screen.crew.student.ndaSigned')}</p> : <NdaText />}
      </div>
      <label className="flex items-start gap-2 text-body-sm">
        <input type="checkbox" className="mt-1" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />
        <span>{t('screen.matching.apply.n5')}</span>
      </label>
      {needsNotice && <CommitEmailNotice understood={understood} onChange={setUnderstood} />}
      <div className="flex flex-wrap justify-between gap-3">
        <Button type="button" variant="secondary" onClick={() => setDeclining(true)} disabled={pending}>
          {t('screen.crew.student.decline')}
        </Button>
        <Button type="button" onClick={submit} disabled={!ready || pending} aria-describedby={`${ids}-why`}>
          {t('screen.crew.student.confirm')}
        </Button>
      </div>
      <p id={`${ids}-why`} className="text-caption text-muted-foreground">
        {hasTerms && !ready ? `${t(needsNotice ? 'screen.crew.student.whyNotice' : 'screen.crew.student.why')} ` : ''}
        {t('screen.crew.student.declineNote')}
      </p>
      <ConfirmDialog
        open={declining}
        title={t('screen.crew.student.declineTitle')}
        body={t('screen.crew.student.declineNote')}
        confirmLabel={t('screen.crew.student.decline')}
        destructive
        pending={decline.isPending}
        onConfirm={leave}
        onClose={() => setDeclining(false)}
      />
    </section>
  )
}

/**
 * A Crew the Student confirmed: their share and the version, nothing of the others', and the way to
 * JOB-8 "Trao đổi nhóm" (read-only while their row is inactive).
 */
function JoinedCard({ assignment }: { assignment: CrewAssignment }) {
  const inactive = assignment.status === 'INACTIVE'
  return (
    <article className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h3 className="text-h3 break-words">{assignment.title}</h3>
          <p className="text-caption text-muted-foreground">
            {t('screen.crew.student.activeMeta', { product: assignment.productName, mentor: assignment.mentorName })}
          </p>
        </div>
        <span
          className={
            inactive
              ? 'rounded-full bg-destructive-soft px-2 py-0.5 text-caption font-semibold text-destructive'
              : 'rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success'
          }
        >
          {t(inactive ? 'screen.crew.student.inactiveBadge' : 'screen.crew.student.activeBadge')}
        </span>
      </div>
      {assignment.sharePercent != null && (
        <p className="text-body-sm">
          {t('screen.crew.student.activeShare', { share: assignment.sharePercent, n: assignment.versionNo })}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {!inactive && hasBoard(assignment.jobState) && (
          <>
            <Link
              to={`/cong-viec/${assignment.jobId}/bang`}
              className={buttonVariants({ variant: 'secondary', size: 'compact' })}
            >
              {t('screen.board.tab')}
            </Link>
            <Link
              to={`/cong-viec/${assignment.jobId}/tai-lieu`}
              className={buttonVariants({ variant: 'secondary', size: 'compact' })}
            >
              {t('screen.docs.tab')}
            </Link>
          </>
        )}
        <Link
          to={`/cong-viec/${assignment.jobId}/trao-doi-nhom`}
          className={buttonVariants({ variant: 'secondary', size: 'compact' })}
        >
          {t('screen.crewChat.tab')}
        </Link>
      </div>
    </article>
  )
}
