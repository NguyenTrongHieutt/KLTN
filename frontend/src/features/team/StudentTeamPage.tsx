import { CircleAlert } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { RegionState } from '@/components/RegionState'
import { Sheet } from '@/components/Sheet'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDate, formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { ConfirmDialog } from './ConfirmDialog'
import { MentorProfileContent } from './MentorProfileContent'
import {
  useAnswerInvitation,
  useLeaveTeam,
  useMentorSearch,
  usePublicMentor,
  useStudentTeam,
  type StudentTeam,
  type TeamMentorRequest,
  type TeamMentorResult,
} from './teamApi'
import { initials } from './teamText'
import { TeamSearchBar } from './TeamSearchBar'

/**
 * STU-6 (`/nhom`, FR-6, FR-14, AD-36, AD-37), the Student's Team: in no Team, the invitations to
 * answer, the Mentor search with PUB-5 in a Sheet, and the join requests sent; in a Team, the
 * Mentor, the listed members, and "Rời nhóm", disabled while on a running Crew.
 */
export function StudentTeamPage() {
  const team = useStudentTeam()
  return (
    <div className="mx-auto flex max-w-[880px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.team.student.heading')}</PageHeading>
      <RegionState query={team} skeleton={<Skeleton className="h-64 w-full" />}>
        {team.data && (team.data.team ? <InTeam team={team.data} /> : <NoTeam team={team.data} />)}
      </RegionState>
    </div>
  )
}

function InTeam({ team }: { team: StudentTeam }) {
  const membership = team.team!
  const mentor = membership.mentor
  const hidden = membership.size - membership.listed.length
  return (
    <>
      {membership.status === 'CLOSED' && (
        <p role="status" className="rounded-md bg-muted p-3 text-body-sm">
          {t('screen.team.student.closedTeam', { mentor: mentor.displayName })}
        </p>
      )}
      <section aria-labelledby="your-mentor-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
        <h2 id="your-mentor-heading" className="text-caption text-muted-foreground">
          {t('screen.team.student.yourMentor')}
        </h2>
        <div className="flex items-start gap-3">
          <Avatar name={mentor.displayName} />
          <div className="flex min-w-0 flex-col gap-1">
            {mentor.githubLogin ? (
              <Link to={`/mentor/${mentor.githubLogin}`} className="text-h3 break-words text-primary underline-offset-4 hover:underline">
                {mentor.displayName}
              </Link>
            ) : (
              <p className="text-h3 break-words">{mentor.displayName}</p>
            )}
            <p className="text-caption text-muted-foreground">
              {t('screen.team.student.mentorSince', {
                years: mentor.yearsOfExperience,
                date: formatDate(membership.joinedAt),
              })}
            </p>
            <SkillChips skills={mentor.skills} />
          </div>
        </div>
      </section>
      <section aria-labelledby="members-heading" className="flex flex-col gap-2">
        <h2 id="members-heading" className="text-h3">
          {t('screen.team.student.members')}
        </h2>
        <p className="text-caption text-muted-foreground">
          {t('screen.team.student.membersLine', { n: membership.size, hidden: Math.max(0, hidden) })}
        </p>
        <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
          {membership.listed.map((member, index) => (
            <li key={`${member.name}-${index}`} className="flex items-center gap-3 px-4 py-2.5">
              <Avatar name={member.name} small />
              <span className="flex min-w-0 flex-col">
                <span className="text-body-sm font-semibold break-words">
                  {member.you ? t('screen.team.student.you', { name: member.name }) : member.name}
                </span>
                {member.school && <span className="text-caption text-muted-foreground">{member.school}</span>}
              </span>
            </li>
          ))}
        </ul>
        <p className="text-caption text-muted-foreground">
          {t('screen.team.student.showNameHint')}{' '}
          <Link to="/ho-so" className="text-primary underline-offset-4 hover:underline">
            {t('screen.team.student.profileLink')}
          </Link>
          .
        </p>
      </section>
      <LeaveTeam mentor={mentor.displayName} onCrew={membership.onCrew} />
    </>
  )
}

function LeaveTeam({ mentor, onCrew }: { mentor: string; onCrew: boolean }) {
  const [confirming, setConfirming] = useState(false)
  const leave = useLeaveTeam()
  const toasts = useToasts()
  return (
    <section aria-labelledby="leave-heading" className="flex flex-col items-start gap-2 rounded-lg border border-border bg-card p-5">
      <h2 id="leave-heading" className="text-h3">
        {t('screen.team.student.leaveHeading')}
      </h2>
      <p id="leave-hint" className="text-body-sm text-muted-foreground">
        {onCrew ? t('screen.team.student.onCrew') : t('screen.team.student.leaveHint')}
      </p>
      <Button
        variant="secondary"
        className="border-destructive text-destructive"
        aria-describedby="leave-hint"
        disabled={onCrew}
        onClick={() => setConfirming(true)}
      >
        {t('screen.team.student.leave')}
      </Button>
      <ConfirmDialog
        open={confirming}
        title={t('screen.team.student.leaveTitle', { mentor })}
        body={t('screen.team.student.leaveBody', { mentor })}
        confirmLabel={t('screen.team.student.leave')}
        destructive
        pending={leave.isPending}
        onClose={() => setConfirming(false)}
        onConfirm={() =>
          leave.mutate(undefined, {
            onSuccess: () => {
              setConfirming(false)
              toasts.success(t('screen.team.student.leftToast', { mentor }))
            },
            onError: (error) => {
              setConfirming(false)
              toasts.error(problemMessage(error, { module: 'team' }).text)
            },
          })
        }
      />
    </section>
  )
}

function NoTeam({ team }: { team: StudentTeam }) {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const skills = (params.get('skills') ?? '').split(',').filter(Boolean)
  const searching = q.length > 0 || skills.length > 0
  const results = useMentorSearch({ q, skills }, searching && team.verified)
  const [viewing, setViewing] = useState<string>()

  function update(next: { q?: string; skills?: string[] }) {
    const search = new URLSearchParams(params)
    const nextQ = next.q ?? q
    const nextSkills = next.skills ?? skills
    if (nextQ) {
      search.set('q', nextQ)
    } else {
      search.delete('q')
    }
    if (nextSkills.length > 0) {
      search.set('skills', nextSkills.join(','))
    } else {
      search.delete('skills')
    }
    setParams(search)
  }

  return (
    <>
      <p className="text-body-sm text-muted-foreground">{t('screen.team.student.noTeamLead')}</p>
      {!team.verified && (
        <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
          <CircleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <p className="text-body-sm">
            {t('screen.team.student.unverified')}{' '}
            <Link to="/ho-so/xac-minh" className="text-primary underline-offset-4 hover:underline">
              {t('screen.team.student.verifyLink')}
            </Link>
          </p>
        </div>
      )}
      {team.invitations.length > 0 && (
        <section aria-labelledby="invitations-heading" className="flex flex-col gap-3">
          <h2 id="invitations-heading" className="text-h3">
            {t('screen.team.student.invitations', { n: team.invitations.length })}
          </h2>
          <ul className="flex flex-col gap-3">
            {team.invitations.map((invitation) => (
              <InvitationCard
                key={invitation.id}
                invitation={invitation}
                verified={team.verified}
                onView={() => invitation.mentor.githubLogin && setViewing(invitation.mentor.githubLogin)}
              />
            ))}
          </ul>
        </section>
      )}
      <section aria-labelledby="find-mentor-heading" className="flex flex-col gap-3">
        <h2 id="find-mentor-heading" className="text-h3">
          {t('screen.team.student.findMentor')}
        </h2>
        {team.verified && (
          <>
            <TeamSearchBar
              key={q}
              id="mentorSearch"
              label={t('screen.team.student.keyword')}
              helper={t('screen.team.student.searchHelper')}
              initial={q}
              onSearch={(next) => update({ q: next })}
            />
            <div className="flex flex-col gap-1.5">
              <label htmlFor="mentorSkills" className="text-label">
                {t('screen.team.skillFilter')}
              </label>
              <SkillPicker id="mentorSkills" value={skills} onChange={(next) => update({ skills: next })} max={20} />
            </div>
            {q && (
              <Button variant="link" className="self-start" onClick={() => update({ q: '' })}>
                {t('screen.team.clearSearch')}
              </Button>
            )}
            {searching && (
              <RegionState query={results} skeleton={<Skeleton className="h-32 w-full" />}>
                {results.data && (
                  <MentorResults
                    results={results.data.items}
                    total={results.data.total}
                    q={q}
                    onView={(login) => setViewing(login)}
                  />
                )}
              </RegionState>
            )}
          </>
        )}
      </section>
      {team.joinRequests.length > 0 && (
        <section aria-labelledby="sent-heading" className="flex flex-col gap-3">
          <h2 id="sent-heading" className="text-h3">
            {t('screen.team.student.sentRequests')}
          </h2>
          <ul className="flex flex-col gap-3">
            {team.joinRequests.map((request) => (
              <li key={request.id} className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-card p-4">
                <Avatar name={request.mentor.displayName} small />
                <span className="flex min-w-0 grow flex-col">
                  <span className="text-body-sm font-semibold break-words">{request.mentor.displayName}</span>
                  <span className="text-caption text-muted-foreground">
                    {t('screen.team.student.sentMeta', {
                      date: formatDayMonth(request.createdAt),
                      lapse: formatDayMonth(request.lapsesAt),
                    })}
                  </span>
                </span>
                <span className="rounded-full bg-muted px-2 py-0.5 text-caption">{t('screen.team.mentor.waiting')}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      <MentorSheet login={viewing} onClose={() => setViewing(undefined)} />
    </>
  )
}

function MentorResults({
  results,
  total,
  q,
  onView,
}: {
  results: TeamMentorResult[]
  total: number
  q: string
  onView: (login: string) => void
}) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-0.5">
        <p className="text-label font-semibold" role="status">
          {q ? t('screen.team.results', { q, n: total }) : t('screen.team.resultsFiltered', { n: total })}
        </p>
        {q && <p className="text-caption text-muted-foreground">{t('screen.team.ranking')}</p>}
      </div>
      {results.length === 0 ? (
        <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">
          {q ? t('screen.team.student.noMentors', { q }) : t('screen.team.student.noMentorsFiltered')}
        </p>
      ) : (
        <ul className="flex flex-col gap-3" aria-label={t('screen.team.student.findMentor')}>
          {results.map((result) => (
            <MentorResultCard key={result.mentor.accountId} result={result} onView={onView} />
          ))}
        </ul>
      )}
    </div>
  )
}

function MentorResultCard({ result, onView }: { result: TeamMentorResult; onView: (login: string) => void }) {
  const mentor = result.mentor
  return (
    <li className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
      <div className="flex items-start gap-3">
        <Avatar name={mentor.displayName} />
        <div className="flex min-w-0 grow flex-col gap-1">
          <p className="text-body-sm font-semibold break-words">{mentor.displayName}</p>
          <p className="text-caption text-muted-foreground">
            {t('screen.team.student.mentorMeta', { years: mentor.yearsOfExperience, n: mentor.teamSize })}
          </p>
          <SkillChips skills={mentor.skills} />
        </div>
      </div>
      <div className="flex flex-wrap items-center justify-end gap-2">
        {result.teamFull && <span className="grow text-caption text-muted-foreground">{t('screen.team.profile.teamFull')}</span>}
        {result.relation === 'REQUESTED' && (
          <span className="grow text-caption text-muted-foreground">{t('screen.team.profile.requestSent')}</span>
        )}
        {mentor.githubLogin && (
          <Button
            variant="secondary"
            size="compact"
            aria-label={t('screen.team.viewProfileOf', { name: mentor.displayName })}
            onClick={() => onView(mentor.githubLogin!)}
          >
            {t('screen.team.viewProfile')}
          </Button>
        )}
      </div>
    </li>
  )
}

function InvitationCard({
  invitation,
  verified,
  onView,
}: {
  invitation: TeamMentorRequest
  verified: boolean
  onView: () => void
}) {
  const [confirming, setConfirming] = useState<'accept' | 'decline'>()
  const answer = useAnswerInvitation()
  const toasts = useToasts()
  const mentor = invitation.mentor
  const accept = confirming === 'accept'
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <div className="flex items-start gap-3">
        <Avatar name={mentor.displayName} />
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="text-body-sm font-semibold break-words">
            {t('screen.team.student.invitationTitle', { mentor: mentor.displayName })}
          </p>
          <p className="text-caption text-muted-foreground">
            {t('screen.team.student.invitationMeta', {
              years: mentor.yearsOfExperience,
              date: formatDayMonth(invitation.createdAt),
              lapse: formatDayMonth(invitation.lapsesAt),
            })}
          </p>
        </div>
      </div>
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="secondary" size="compact" onClick={onView}>
          {t('screen.team.viewProfile')}
        </Button>
        <Button variant="secondary" size="compact" onClick={() => setConfirming('decline')}>
          {t('screen.team.student.decline')}
        </Button>
        <Button size="compact" disabled={!verified} onClick={() => setConfirming('accept')}>
          {t('screen.team.student.agree')}
        </Button>
      </div>
      <ConfirmDialog
        open={confirming !== undefined}
        title={t(accept ? 'screen.team.student.acceptTitle' : 'screen.team.student.declineTitle', {
          mentor: mentor.displayName,
        })}
        body={t(accept ? 'screen.team.student.acceptBody' : 'screen.team.student.declineBody', {
          mentor: mentor.displayName,
        })}
        confirmLabel={accept ? t('screen.team.student.agree') : t('screen.team.student.decline')}
        destructive={!accept}
        pending={answer.isPending}
        onClose={() => setConfirming(undefined)}
        onConfirm={() =>
          answer.mutate(
            { invitationId: invitation.id, accept },
            {
              onSuccess: () => {
                setConfirming(undefined)
                toasts.success(
                  t(accept ? 'screen.team.student.joinedToast' : 'screen.team.student.declinedToast', {
                    mentor: mentor.displayName,
                  }),
                )
              },
              onError: (error) => {
                setConfirming(undefined)
                toasts.error(problemMessage(error, { module: 'team' }).text)
              },
            },
          )
        }
      />
    </li>
  )
}

/** PUB-5 in a Sheet, with "Mở hồ sơ đầy đủ". */
function MentorSheet({ login, onClose }: { login?: string; onClose: () => void }) {
  const mentor = usePublicMentor(login)
  return (
    <Sheet
      open={login !== undefined}
      onClose={onClose}
      title={mentor.data?.displayName ?? t('screen.team.student.findMentor')}
      footer={
        login && (
          <Link to={`/mentor/${login}`} className="text-body-sm text-primary underline-offset-4 hover:underline">
            {t('screen.team.profile.openFull')}
          </Link>
        )
      }
    >
      <RegionState query={mentor} skeleton={<Skeleton className="h-64 w-full" />}>
        {mentor.data && <MentorProfileContent mentor={mentor.data} visitor={false} />}
      </RegionState>
    </Sheet>
  )
}

function SkillChips({ skills }: { skills: string[] }) {
  if (skills.length === 0) {
    return null
  }
  return (
    <ul className="flex flex-wrap gap-1.5">
      {skills.map((skill) => (
        <li key={skill} className="rounded-full border border-border px-2 py-0.5 text-caption">
          {skill}
        </li>
      ))}
    </ul>
  )
}

function Avatar({ name, small = false }: { name: string; small?: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={
        small
          ? 'flex size-8 shrink-0 items-center justify-center rounded-full bg-muted text-caption'
          : 'flex size-10 shrink-0 items-center justify-center rounded-full bg-primary-soft text-label text-primary'
      }
    >
      {initials(name)}
    </span>
  )
}

