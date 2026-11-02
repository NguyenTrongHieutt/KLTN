import { Dialog } from '@base-ui/react/dialog'
import { TriangleAlert, X } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Sheet } from '@/components/Sheet'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { PersonProfileContent } from '@/features/identity/PersonProfile'
import { VerifiedFactLines } from '@/features/identity/VerifiedFacts'
import { CrewLeftLines } from '@/features/crew/NoCrew'
import { JobPhaseBadge, JobRoleLine } from '@/features/job/ByJobRole'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { useReputationHistories, type Histories } from '@/features/reputation/historyApi'
import { HistoryRegion, RoleSummaryLine } from '@/features/reputation/RoleHistories'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { useJobProgress } from '@/features/work/progress/progressApi'
import { hasBoard } from '@/features/work/workApi'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  MAX_CREW_SKILLS,
  MAX_RECRUIT_NOTE,
  useDeclineCrewApplication,
  useJobCrewApplications,
  useRecruitment,
  useSetRecruitment,
  type ApplicantCard,
  type Recruitment,
  type RecruitmentMember,
} from './crewApplicationApi'
import { compareApplicantsPath, MAX_COMPARED } from './compare'
import { PickDialog, WithdrawPickDialog } from './PickDialogs'
import { SkillChips } from './SkillChips'
import { FitMetricsList } from './FitMetrics'
import { MentorJobTabs } from './MentorJobTabs'
import { useMentorJob, type MentorJob } from './mentorJobApi'
import { ProofList } from './ProofList'

/**
 * JOB-9 Tuyển thành viên (`/cong-viec/{jobId}/tuyen-thanh-vien`, FR-75, FR-81, UX-DR102, Story 21.4), for the Job's
 * Mentor only: anyone else, the Customer included, gets the not-found screen. The switch "Nhận đơn xin vào nhóm" with
 * "Cần thêm {n} người" (1 up to the free places under the Crew cap), the Skills sought and a note, under N-20; turning
 * it off asks first, since the open applications lapse. In Agreement with no member signed, R7's warning. Then the
 * Crew Applications as Applicant cards, oldest first and never reordered, each with the applicant's Verified facts, the
 * note, Skills and proof, the histories as Mentor and as Crew member, and the Fit metrics against the Skills sought,
 * with "Xem hồ sơ" (PUB-4 in a Sheet), "So sánh" (up to three, the CUS-13 component), "Chọn" (a confirmation with the
 * signing deadline) and "Không chọn" (no reason asked); a pick shows "Đã chọn · chờ ký trước {date}" with "Rút lựa
 * chọn" (R4, Story 21.5); the closed ones under "Đơn đã đóng"; and the Crew list with join and leave dates, the reason
 * a member gave when leaving, which only the Mentor reads (FR-82), and no remove action: nobody who signed is removed
 * (FR-75). While a former member's open Work Items wait, their line says so (Story 21.6).
 */
export function RecruitmentPage() {
  const { jobId } = useParams()
  const recruitment = useRecruitment(jobId)
  const job = useMentorJob(jobId)
  usePageTitle(job.data ? `${t('screen.recruitment.title')} · ${job.data.title}` : t('screen.recruitment.title'))
  if ((recruitment.isError && isNotFound(recruitment.error)) || (job.isError && isNotFound(job.error))) {
    return <NotFoundScreen />
  }
  const both = {
    isPending: recruitment.isPending || job.isPending,
    isError: recruitment.isError || job.isError,
    error: recruitment.error ?? job.error,
    refetch: () => Promise.all([recruitment.refetch(), job.refetch()]),
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={both} skeleton={<Skeleton className="h-96 w-full" />}>
        {recruitment.data && job.data && <Recruiting job={job.data} recruitment={recruitment.data} />}
      </RegionState>
    </div>
  )
}

function Recruiting({ job, recruitment }: { job: MentorJob; recruitment: Recruitment }) {
  const progress = useJobProgress(hasBoard(job.state) ? job.id : undefined)
  return (
    <>
      <div className="flex flex-col gap-3">
        <nav aria-label={t('screen.job.detail.breadcrumb')} className="text-caption text-muted-foreground">
          <Link to="/cong-viec" className="text-primary underline-offset-4 hover:underline">
            {t('screen.job.detail.breadcrumb')}
          </Link>{' '}
          / <span className="break-words">{job.title}</span>
        </nav>
        <div className="flex flex-wrap items-center gap-2">
          <PageHeading>{t('screen.recruitment.title')}</PageHeading>
          <JobStateBadge state={job.state} audience="mentor" />
          <JobPhaseBadge jobId={job.id} />
        </div>
        <p className="text-body-sm break-words text-muted-foreground">{job.title}</p>
        <JobRoleLine jobId={job.id} />
        <MentorJobTabs jobId={job.id} state={job.state} current="recruitment" payment={job.agreementConfirmed} />
      </div>
      <CrewLeftLines leavers={progress.data?.leavers} />
      {recruitment.state === 'AGREEMENT' && !recruitment.signedMember && (
        <div role="alert" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <p className="break-words">{t('screen.recruitment.r7')}</p>
        </div>
      )}
      <Settings key={recruitment.crewVersion} jobId={job.id} recruitment={recruitment} />
      <Applicants jobId={job.id} />
      <CrewList crew={recruitment.crew} />
    </>
  )
}

/** "Nhận đơn xin vào nhóm" under N-20 (UX-DR102, R2); off while on asks first, since the open applications lapse. */
function Settings({ jobId, recruitment }: { jobId: string; recruitment: Recruitment }) {
  const ids = useId()
  const toasts = useToasts()
  const save = useSetRecruitment(jobId)
  const [on, setOn] = useState(recruitment.recruiting)
  const [slots, setSlots] = useState(String(recruitment.recruiting ? recruitment.openSlots : Math.min(1, recruitment.maxSlots)))
  const [skillIds, setSkillIds] = useState(recruitment.skills.map((skill) => skill.id))
  const [note, setNote] = useState(recruitment.note ?? '')
  const [confirmingOff, setConfirmingOff] = useState(false)
  const [error, setError] = useState<string>()
  const full = recruitment.maxSlots === 0

  if (!recruitment.available) {
    return (
      <section aria-labelledby={`${ids}-heading`} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
        <h2 id={`${ids}-heading`} className="text-h2">
          {t('screen.recruitment.settings')}
        </h2>
        <p role="status" className="text-body-sm text-muted-foreground">
          {t('screen.recruitment.unavailable')}
        </p>
      </section>
    )
  }

  const send = (turnOn: boolean) => {
    setError(undefined)
    const openSlots = Number.parseInt(slots, 10)
    if (turnOn && (!Number.isInteger(openSlots) || openSlots < 1 || openSlots > recruitment.maxSlots)) {
      setError(t('screen.recruitment.slotsInvalid', { max: recruitment.maxSlots }))
      return
    }
    save.mutate(
      {
        on: turnOn,
        openSlots: turnOn ? openSlots : undefined,
        skillIds,
        note: note.trim() === '' ? undefined : note.trim(),
        crewVersion: recruitment.crewVersion,
      },
      {
        onSuccess: () => {
          setConfirmingOff(false)
          toasts.success(t('screen.recruitment.saved'))
        },
        onError: (failed) => {
          setConfirmingOff(false)
          if (failed instanceof ApiError && failed.problem.reason === 'SLOTS_INVALID') {
            setError(t('screen.recruitment.slotsInvalid', { max: (failed.problem as { max?: number }).max ?? 0 }))
            return
          }
          if (failed instanceof ApiError && failed.problem.code === 'STALE') {
            // The recruitment is read again and the form starts over at the new Crew version: a toast outlives it.
            toasts.error(t('screen.recruitment.stale'))
            return
          }
          setError(problemMessage(failed, { module: 'job' }).text)
        },
      },
    )
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!on && recruitment.recruiting) {
      setConfirmingOff(true)
      return
    }
    send(on)
  }

  return (
    <section aria-labelledby={`${ids}-heading`} className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5">
      <h2 id={`${ids}-heading`} className="text-h2">
        {t('screen.recruitment.settings')}
      </h2>
      <div role="note" className="rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
        {t('screen.recruitment.n20')}
      </div>
      <p role="status" className="text-label font-semibold">
        {recruitment.recruiting
          ? t('screen.recruitment.summaryOn', { n: recruitment.openSlots })
          : t('screen.recruitment.summaryOff')}
      </p>
      {full && !recruitment.recruiting && (
        <p className="text-body-sm text-muted-foreground">{t('screen.recruitment.full', { n: recruitment.cap })}</p>
      )}
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        <label className="flex items-center gap-3 text-body-sm">
          <input
            type="checkbox"
            role="switch"
            aria-checked={on}
            checked={on}
            disabled={full && !recruitment.recruiting}
            onChange={(event) => setOn(event.target.checked)}
          />
          <span className="text-label">{t('screen.recruitment.switch')}</span>
        </label>
        {on && (
          <>
            <FormField
              id={`${ids}-slots`}
              label={t('screen.recruitment.slots')}
              helper={t('screen.recruitment.slotsHint', { max: recruitment.maxSlots })}
            >
              <input
                {...fieldProps(`${ids}-slots`, undefined, t('screen.recruitment.slotsHint', { max: recruitment.maxSlots }))}
                type="number"
                inputMode="numeric"
                min={1}
                max={recruitment.maxSlots}
                value={slots}
                onChange={(event) => setSlots(event.target.value)}
                className={cn(inputClass, 'max-w-32')}
              />
            </FormField>
            <div className="flex flex-col gap-1.5">
              <label htmlFor={`${ids}-skills`} className="text-label">
                {t('screen.recruitment.skills')}
              </label>
              <SkillPicker
                id={`${ids}-skills`}
                value={skillIds}
                onChange={setSkillIds}
                known={recruitment.skills}
                max={MAX_CREW_SKILLS}
                describedBy={`${ids}-skills-hint`}
              />
              <p id={`${ids}-skills-hint`} className="text-caption text-muted-foreground">
                {t('screen.recruitment.skillsHint')}
              </p>
            </div>
            <FormField id={`${ids}-note`} label={t('screen.recruitment.note')} helper={t('screen.recruitment.noteHint')}>
              <textarea
                {...fieldProps(`${ids}-note`, undefined, t('screen.recruitment.noteHint'))}
                rows={3}
                maxLength={MAX_RECRUIT_NOTE}
                value={note}
                onChange={(event) => setNote(event.target.value)}
                className={cn(inputClass, 'min-h-20')}
              />
            </FormField>
          </>
        )}
        {error && (
          <p role="alert" className="text-body-sm text-destructive">
            {error}
          </p>
        )}
        <Button type="submit" className="w-fit" disabled={save.isPending || (!on && !recruitment.recruiting)}>
          {t(save.isPending ? 'screen.recruitment.saving' : 'screen.recruitment.save')}
        </Button>
      </form>
      <ConfirmDialog
        open={confirmingOff}
        title={t('screen.recruitment.offTitle')}
        body={t('screen.recruitment.offBody')}
        back={t('screen.recruitment.offKeep')}
        confirm={t('screen.recruitment.offConfirm')}
        pending={save.isPending}
        onClose={() => setConfirmingOff(false)}
        onConfirm={() => send(false)}
      />
    </section>
  )
}

/** How a closed application ended, under "Đơn đã đóng": not picked, a pick that ended or joined, or lapsed. */
const CLOSED: Partial<Record<string, MessageKey>> = {
  DECLINED: 'screen.recruitment.closedDECLINED',
  COMMITMENT_DECLINED: 'screen.recruitment.closedCOMMITMENT_DECLINED',
  COMMITMENT_LAPSED: 'screen.recruitment.closedCOMMITMENT_LAPSED',
  PICK_WITHDRAWN: 'screen.recruitment.closedPICK_WITHDRAWN',
  PICK_ENDED: 'screen.recruitment.closedPICK_ENDED',
  SIGNED: 'screen.recruitment.closedSIGNED',
}

function closedLabel(application: ApplicantCard): string {
  return t(CLOSED[application.endReason ?? ''] ?? 'screen.recruitment.closedLAPSED')
}

/** An application still open on JOB-9: pending, or picked and waiting for its signature. */
function isOpen(item: ApplicantCard): boolean {
  return item.status === 'PENDING' || item.status === 'PICKED'
}

/**
 * JOB-9's Applicant cards (UX-DR101, UX-DR102): the open ones (pending and picked) oldest first, then "Đơn đã đóng";
 * "So sánh" ticks up to three for the comparison.
 */
function Applicants({ jobId }: { jobId: string }) {
  const ids = useId()
  const applications = useJobCrewApplications(jobId)
  const items = applications.data ?? []
  const open = items.filter(isOpen)
  const closed = items.filter((item) => !isOpen(item))
  const histories = useReputationHistories(
    open.map((item) => item.applicant.githubLogin).filter((login): login is string => !!login),
  )
  const [profile, setProfile] = useState<ApplicantCard>()
  const [declining, setDeclining] = useState<ApplicantCard>()
  const [picking, setPicking] = useState<ApplicantCard>()
  const [withdrawing, setWithdrawing] = useState<ApplicantCard>()
  const [compared, setCompared] = useState<string[]>([])
  const comparing = compared.filter((id) => open.some((item) => item.id === id))
  const toggle = (id: string) =>
    setCompared((current) => (current.includes(id) ? current.filter((other) => other !== id) : [...current, id]))
  return (
    <section aria-labelledby={`${ids}-heading`} className="flex flex-col gap-3">
      <h2 id={`${ids}-heading`} className="text-h2">
        {t('screen.recruitment.applications')}
      </h2>
      <RegionState query={applications} skeleton={<Skeleton className="h-48 w-full" />}>
        {open.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.recruitment.applicationsEmpty')}</p>
        ) : (
          <>
            {comparing.length > 0 && (
              <div role="status" className="flex flex-wrap items-center gap-3 rounded-md border border-border bg-card px-4 py-2.5">
                <span className="text-body-sm">
                  {t('screen.recruitment.compareCount', { n: comparing.length, max: MAX_COMPARED })}
                </span>
                {comparing.length >= 2 ? (
                  <Link to={compareApplicantsPath(jobId, comparing)} className={buttonVariants({ size: 'compact' })}>
                    {t('screen.recruitment.compareOpen')}
                  </Link>
                ) : (
                  <span className="text-caption text-muted-foreground">{t('screen.recruitment.compareMore')}</span>
                )}
              </div>
            )}
            <ul className="flex flex-col gap-4">
              {open.map((application) => (
                <ApplicantCardItem
                  key={application.id}
                  application={application}
                  histories={histories}
                  compared={comparing.includes(application.id)}
                  compareFull={comparing.length >= MAX_COMPARED}
                  onCompare={() => toggle(application.id)}
                  onProfile={() => setProfile(application)}
                  onPick={() => setPicking(application)}
                  onWithdrawPick={() => setWithdrawing(application)}
                  onDecline={() => setDeclining(application)}
                />
              ))}
            </ul>
          </>
        )}
        {closed.length > 0 && (
          <div className="flex flex-col gap-2">
            <h3 className="text-h3">{t('screen.recruitment.closed')}</h3>
            <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
              {closed.map((application) => (
                <li key={application.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-body-sm">
                  <span className="font-semibold break-words">{application.applicant.displayName}</span>
                  <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">{closedLabel(application)}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </RegionState>
      <Sheet
        open={profile !== undefined}
        onClose={() => setProfile(undefined)}
        title={profile?.applicant.displayName ?? t('screen.recruitment.sheetTitle')}
        description={t('screen.recruitment.sheetTitle')}
      >
        {profile?.applicant.githubLogin && <PersonProfileContent username={profile.applicant.githubLogin} level={4} />}
      </Sheet>
      {declining && <DeclineDialog jobId={jobId} application={declining} onClose={() => setDeclining(undefined)} />}
      {picking && <PickDialog jobId={jobId} application={picking} onClose={() => setPicking(undefined)} />}
      {withdrawing && (
        <WithdrawPickDialog jobId={jobId} application={withdrawing} onClose={() => setWithdrawing(undefined)} />
      )}
    </section>
  )
}

/**
 * One Applicant card in the Bid card's order (UX-DR101): the Verified facts; the note, Skills and proof; the histories
 * as Mentor and as Crew member; then the Fit metrics against the Skills sought. No score, rank or highlight. A pending
 * one offers "So sánh", "Chọn" and "Không chọn"; a pick waits for its signature with "Rút lựa chọn" (R4).
 */
function ApplicantCardItem({
  application,
  histories,
  compared,
  compareFull,
  onCompare,
  onProfile,
  onPick,
  onWithdrawPick,
  onDecline,
}: {
  application: ApplicantCard
  histories: Histories
  compared: boolean
  compareFull: boolean
  onCompare: () => void
  onProfile: () => void
  onPick: () => void
  onWithdrawPick: () => void
  onDecline: () => void
}) {
  const picked = application.status === 'PICKED'
  const ids = useId()
  const applicant = application.applicant
  const login = applicant.githubLogin
  const history = login ? histories.byUsername.get(login) : undefined
  const lines = (
    <div className="grid gap-2 sm:grid-cols-2">
      <RoleSummaryLine role="mentor" summary={history?.mentor} newcomer={history?.newcomer ?? false} />
      <RoleSummaryLine role="crew" summary={history?.crew} newcomer={false} />
    </div>
  )
  return (
    <li aria-labelledby={`${ids}-name`} className="flex flex-col gap-4 rounded-lg border border-border bg-card p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span id={`${ids}-name`} className="text-label font-semibold break-words">
          {applicant.displayName}
        </span>
        {picked && application.signDueAt ? (
          <span role="status" className="rounded-full bg-primary-soft px-2 py-0.5 text-caption font-semibold text-primary">
            {t('screen.recruitment.pickedChip', { date: formatDayMonth(application.signDueAt) })}
          </span>
        ) : (
          <span className="text-caption text-muted-foreground">
            {t('screen.recruitment.sentOn', {
              date: formatDayMonth(application.submittedAt),
              lapse: formatDayMonth(application.lapsesAt),
            })}
          </span>
        )}
      </div>
      <section aria-label={t('screen.matching.bid.facts')} className="flex flex-col gap-1.5">
        <p className="text-caption font-semibold text-muted-foreground">{t('screen.matching.bid.facts')}</p>
        <VerifiedFactLines facts={applicant.verified} compact />
      </section>
      <section aria-label={t('screen.recruitment.posted')} className="flex flex-col gap-2">
        <p className="text-caption font-semibold text-muted-foreground">{t('screen.recruitment.posted')}</p>
        <p className="text-body-sm break-words whitespace-pre-line">{application.note}</p>
        {application.skills.length > 0 ? (
          <SkillChips names={application.skills.map((skill) => skill.name)} />
        ) : (
          <p className="text-caption text-muted-foreground">{t('screen.recruitment.noSkills')}</p>
        )}
        <ProofList proof={application.proof} />
      </section>
      <section aria-label={t('screen.matching.bid.history')} className="flex flex-col gap-2">
        <p className="text-caption font-semibold text-muted-foreground">{t('screen.matching.bid.history')}</p>
        {login ? <HistoryRegion histories={histories}>{lines}</HistoryRegion> : lines}
      </section>
      <section aria-label={t('screen.matching.bid.metrics')} className="flex flex-col gap-2">
        <p className="text-caption font-semibold text-muted-foreground">{t('screen.matching.bid.metrics')}</p>
        <FitMetricsList
          metrics={application.fitMetrics ?? undefined}
          histories={histories}
          username={login ?? undefined}
          memberSince={applicant.memberSince ?? undefined}
        />
      </section>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="secondary" onClick={onProfile} disabled={!login}>
          {t('screen.recruitment.profile')}
        </Button>
        <Button
          type="button"
          variant="secondary"
          aria-pressed={compared}
          onClick={onCompare}
          disabled={!compared && compareFull}
        >
          {t(compared ? 'screen.recruitment.compareRemove' : 'screen.recruitment.compareToggle')}
        </Button>
        {picked ? (
          <Button type="button" variant="secondary" onClick={onWithdrawPick}>
            {t('screen.recruitment.withdrawPick')}
          </Button>
        ) : (
          <>
            <Button type="button" onClick={onPick}>
              {t('screen.recruitment.pick')}
            </Button>
            <Button type="button" variant="secondary" onClick={onDecline}>
              {t('screen.recruitment.decline')}
            </Button>
          </>
        )}
      </div>
    </li>
  )
}

/** "Không chọn" asks for no reason; the applicant is told only that they were not picked. */
function DeclineDialog({ jobId, application, onClose }: { jobId: string; application: ApplicantCard; onClose: () => void }) {
  const decline = useDeclineCrewApplication(jobId)
  const toasts = useToasts()
  const name = application.applicant.displayName
  const confirm = () =>
    decline.mutate(application.id, {
      onSuccess: () => {
        onClose()
        toasts.success(t('screen.recruitment.declined', { name }))
      },
      onError: (error) => {
        onClose()
        toasts.error(problemMessage(error, { module: 'matching' }).text)
      },
    })
  return (
    <ConfirmDialog
      open
      title={t('screen.recruitment.declineTitle', { name })}
      body={t('screen.recruitment.declineBody', { name })}
      back={t('screen.recruitment.declineBack')}
      confirm={t('screen.recruitment.decline')}
      pending={decline.isPending}
      onClose={onClose}
      onConfirm={confirm}
    />
  )
}

/** JOB-9's Crew list (UX-DR102): who joined and when, who left and when (a lock reads the same), no remove action. */
function CrewList({ crew }: { crew: RecruitmentMember[] }) {
  const ids = useId()
  return (
    <section aria-labelledby={`${ids}-heading`} className="flex flex-col gap-2">
      <h2 id={`${ids}-heading`} className="text-h2">
        {t('screen.recruitment.crew')}
      </h2>
      {crew.length === 0 ? (
        <p className="text-body-sm text-muted-foreground">{t('screen.recruitment.crewEmpty')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
          {crew.map((member) => (
            <li key={member.rowId} className="flex flex-col gap-1 px-4 py-3 text-body-sm">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-semibold break-words">{member.name}</span>
                <span className="text-muted-foreground">{memberText(member)}</span>
              </div>
              {member.leftNote && (
                <p className="text-caption text-muted-foreground break-words whitespace-pre-line">
                  {t('screen.recruitment.memberLeftNote', { note: member.leftNote })}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function memberText(member: RecruitmentMember): string {
  if (member.status === 'RELEASED') {
    return t('screen.recruitment.memberLeft', { date: member.leftAt ? formatDayMonth(member.leftAt) : '' })
  }
  if (member.status === 'ACTIVE') {
    return t('screen.recruitment.memberActive', { date: member.joinedAt ? formatDayMonth(member.joinedAt) : '' })
  }
  return t('screen.recruitment.memberPicked')
}

function ConfirmDialog({
  open,
  title,
  body,
  back,
  confirm,
  pending,
  onClose,
  onConfirm,
}: {
  open: boolean
  title: string
  body: string
  back: string
  confirm: string
  pending: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2">{title}</Dialog.Title>
            <Dialog.Close aria-label={t('screen.product.finding.close')} className="rounded-md p-1 text-muted-foreground hover:bg-muted">
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          <Dialog.Description className="text-body-sm">{body}</Dialog.Description>
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{back}</Dialog.Close>
            <Button type="button" variant="destructive" onClick={onConfirm} disabled={pending}>
              {confirm}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
