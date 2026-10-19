import { Info } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Sheet } from '@/components/Sheet'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDate, formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { ConfirmDialog } from './ConfirmDialog'
import {
  useAnswerJoinRequest,
  useInviteStudent,
  useRemoveMember,
  useMentorTeam,
  useStudentByLogin,
  useStudentSearch,
  useTeamStudentProfile,
  type MentorTeam,
  type TeamMember,
  type TeamStudent,
  type TeamStudentRequest,
  type TeamStudentResult,
} from './teamApi'
import { initials, studentName } from './teamText'
import { TeamSearchBar } from './TeamSearchBar'

/**
 * MEN-6 (`/nhom`, FR-6, AD-37), the Mentor's Team: the size "{n}/10", join requests to accept or
 * decline, the members, the pending invitations, and "Mời sinh viên", which opens the invite Sheet.
 * "Xóa khỏi nhóm" goes through the Confirmation dialog and is disabled while the Student is on a
 * running Crew. A closed Team (the Mentor revoked or locked) shows why and no invitations. "Đang làm:
 * {Job}" comes with the Crews.
 */
export function MentorTeamPage() {
  const team = useMentorTeam()
  const [inviting, setInviting] = useState(false)
  const [viewing, setViewing] = useState<string>()
  const open = team.data?.status === 'OPEN'

  return (
    <div className="mx-auto flex max-w-[960px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <PageHeading>{t('screen.team.mentor.heading')}</PageHeading>
          <p className="text-body-sm text-muted-foreground">{t('screen.team.mentor.lead')}</p>
        </div>
        {team.data && (
          <div className="flex items-center gap-3">
            <span className="rounded-full bg-muted px-3 py-1 text-label">
              {t('screen.team.mentor.size', { n: team.data.size })}
            </span>
            {open && <Button onClick={() => setInviting(true)}>{t('screen.team.mentor.invite')}</Button>}
          </div>
        )}
      </div>
      <RegionState query={team} skeleton={<Skeleton className="h-64 w-full" />}>
        {team.data && <TeamBody team={team.data} onView={setViewing} onInvite={() => setInviting(true)} />}
      </RegionState>
      {team.data && open && (
        <InviteSheet team={team.data} open={inviting} onClose={() => setInviting(false)} onView={setViewing} />
      )}
      <StudentSheet studentId={viewing} onClose={() => setViewing(undefined)} />
    </div>
  )
}

function TeamBody({ team, onView, onInvite }: { team: MentorTeam; onView: (id: string) => void; onInvite: () => void }) {
  const open = team.status === 'OPEN'
  return (
    <>
      {!open && (
        <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
          <Info aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <div className="flex flex-col gap-0.5">
            <p className="text-label font-semibold">{t('screen.team.mentor.closed')}</p>
            <p className="text-body-sm">{t('screen.team.mentor.closedBody')}</p>
          </div>
        </div>
      )}
      {open && team.size === 0 && (
        <div className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4">
          <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
          <p className="text-body-sm">{t('screen.team.mentor.noFreeStudent')}</p>
        </div>
      )}
      <section aria-labelledby="join-requests-heading" className="flex flex-col gap-3">
        <h2 id="join-requests-heading" className="text-h3">
          {t('screen.team.mentor.joinRequests', { n: team.joinRequests.length })}
        </h2>
        {team.joinRequests.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.team.mentor.joinRequestsEmpty')}</p>
        ) : (
          <ul className="flex flex-col gap-3">
            {team.joinRequests.map((request) => (
              <JoinRequestCard key={request.id} request={request} team={team} onView={onView} />
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="members-heading" className="flex flex-col gap-3">
        <h2 id="members-heading" className="text-h3">
          {t('screen.team.mentor.members')}
        </h2>
        {team.members.length === 0 ? (
          <div className="flex flex-col items-start gap-3 rounded-lg border border-dashed border-border bg-card p-6">
            <p className="text-body-sm">{t('screen.team.mentor.empty')}</p>
            <p className="text-caption text-muted-foreground">{t('screen.team.mentor.emptyHint')}</p>
            {open && (
              <Button variant="secondary" onClick={onInvite}>
                {t('screen.team.mentor.invite')}
              </Button>
            )}
          </div>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full text-left text-body-sm" aria-label={t('screen.team.mentor.membersLabel')}>
              <thead className="bg-muted text-caption text-muted-foreground">
                <tr>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.team.mentor.colStudent')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.team.mentor.colGithub')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.team.mentor.colStatus')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    <span className="sr-only">{t('screen.team.mentor.colActions')}</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {team.members.map((member) => (
                  <tr key={member.membershipId}>
                    <td className="px-4 py-2">
                      <button
                        type="button"
                        onClick={() => onView(member.student.accountId)}
                        className="flex items-center gap-2 rounded-sm text-left font-semibold hover:text-primary focus-visible:outline-2 focus-visible:outline-ring"
                      >
                        <Avatar name={studentName(member.student)} small />
                        <span className="break-words">{studentName(member.student)}</span>
                      </button>
                    </td>
                    <td className="px-4 py-2">{member.student.githubLogin}</td>
                    <td className="px-4 py-2">
                      <MemberStatus student={member.student} />
                    </td>
                    <td className="px-4 py-2 text-right">
                      <RemoveMember member={member} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {team.members.some((member) => member.onCrew) && (
          <p className="text-caption text-muted-foreground">
            {t('screen.team.mentor.onCrew', {
              names: team.members
                .filter((member) => member.onCrew)
                .map((member) => studentName(member.student))
                .join(', '),
            })}
          </p>
        )}
      </section>
      {team.invitations.length > 0 && (
        <section aria-labelledby="invitations-heading" className="flex flex-col gap-3">
          <h2 id="invitations-heading" className="text-h3">
            {t('screen.team.mentor.invitations')}
          </h2>
          <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
            {team.invitations.map((invitation) => (
              <li key={invitation.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <Avatar name={studentName(invitation.student)} small />
                <span className="flex min-w-0 grow flex-col">
                  <span className="text-body-sm font-semibold break-words">{studentName(invitation.student)}</span>
                  <span className="text-caption text-muted-foreground">
                    {t('screen.team.mentor.invitationMeta', {
                      date: formatDayMonth(invitation.createdAt),
                      lapse: formatDayMonth(invitation.lapsesAt),
                    })}
                  </span>
                </span>
                <span className="rounded-full bg-muted px-2 py-0.5 text-caption">{t('screen.team.mentor.waiting')}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}

function RemoveMember({ member }: { member: TeamMember }) {
  const [confirming, setConfirming] = useState(false)
  const remove = useRemoveMember()
  const toasts = useToasts()
  const name = studentName(member.student)
  return (
    <>
      <Button
        variant="ghost"
        size="compact"
        className="text-destructive"
        aria-label={t('screen.team.mentor.removeOf', { name })}
        disabled={member.onCrew}
        onClick={() => setConfirming(true)}
      >
        {t('screen.team.mentor.remove')}
      </Button>
      <ConfirmDialog
        open={confirming}
        title={t('screen.team.mentor.removeTitle', { name })}
        body={t('screen.team.mentor.removeBody', { name })}
        confirmLabel={t('screen.team.mentor.remove')}
        destructive
        pending={remove.isPending}
        onClose={() => setConfirming(false)}
        onConfirm={() =>
          remove.mutate(member.membershipId, {
            onSuccess: () => {
              setConfirming(false)
              toasts.success(t('screen.team.mentor.removedToast', { name }))
            },
            onError: (error) => {
              setConfirming(false)
              toasts.error(problemMessage(error, { module: 'team' }).text)
            },
          })
        }
      />
    </>
  )
}

function MemberStatus({ student }: { student: TeamStudent }) {
  return (
    <span className="flex flex-col gap-0.5">
      <span>{t('screen.team.mentor.free')}</span>
      {!student.verified && student.verifiedUntil && (
        <span className="text-caption text-warning">
          {t('screen.team.mentor.verificationExpired', { date: formatDayMonth(`${student.verifiedUntil}T12:00:00+07:00`) })}
        </span>
      )}
    </span>
  )
}

function JoinRequestCard({
  request,
  team,
  onView,
}: {
  request: TeamStudentRequest
  team: MentorTeam
  onView: (id: string) => void
}) {
  const [confirming, setConfirming] = useState<'accept' | 'decline'>()
  const answer = useAnswerJoinRequest()
  const toasts = useToasts()
  const name = studentName(request.student)
  const accept = confirming === 'accept'
  const open = team.status === 'OPEN'
  const full = team.size >= team.sizeLimit
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <div className="flex items-start gap-3">
        <Avatar name={name} />
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="flex flex-wrap items-center gap-2 text-body-sm font-semibold">
            <span className="break-words">{name}</span>
            {request.student.verified && (
              <span className="rounded-full bg-success-soft px-2 py-0.5 text-caption text-success">
                {t('screen.team.verified')}
              </span>
            )}
          </p>
          <p className="text-caption text-muted-foreground">
            {[request.student.school, request.student.skills.join(', ')].filter(Boolean).join(' · ')}
            {' · '}
            {t('screen.team.mentor.requestMeta', {
              date: formatDayMonth(request.createdAt),
              lapse: formatDayMonth(request.lapsesAt),
            })}
          </p>
        </div>
      </div>
      <div className="flex flex-wrap items-center justify-end gap-2">
        {full && <span className="grow text-caption text-muted-foreground">{t('screen.team.mentor.teamFull')}</span>}
        <Button
          variant="secondary"
          size="compact"
          aria-label={t('screen.team.viewProfileOf', { name })}
          onClick={() => onView(request.student.accountId)}
        >
          {t('screen.team.viewProfile')}
        </Button>
        <Button variant="secondary" size="compact" onClick={() => setConfirming('decline')}>
          {t('screen.team.mentor.decline')}
        </Button>
        <Button size="compact" disabled={!open || full} onClick={() => setConfirming('accept')}>
          {t('screen.team.mentor.accept')}
        </Button>
      </div>
      <ConfirmDialog
        open={confirming !== undefined}
        title={t(accept ? 'screen.team.mentor.acceptTitle' : 'screen.team.mentor.declineTitle', { name })}
        body={t(accept ? 'screen.team.mentor.acceptBody' : 'screen.team.mentor.declineBody', {
          name,
          n: team.size + 1,
        })}
        confirmLabel={accept ? t('screen.team.mentor.accept') : t('screen.team.mentor.decline')}
        destructive={!accept}
        pending={answer.isPending}
        onClose={() => setConfirming(undefined)}
        onConfirm={() =>
          answer.mutate(
            { joinRequestId: request.id, accept },
            {
              onSuccess: () => {
                setConfirming(undefined)
                toasts.success(t(accept ? 'screen.team.mentor.acceptedToast' : 'screen.team.mentor.declinedToast', { name }))
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

/** The invite-to-Team Sheet: the Student search, and the invitation by exact GitHub username. */
function InviteSheet({
  team,
  open,
  onClose,
  onView,
}: {
  team: MentorTeam
  open: boolean
  onClose: () => void
  onView: (id: string) => void
}) {
  const [q, setQ] = useState('')
  const [skills, setSkills] = useState<string[]>([])
  const searching = q.length > 0 || skills.length > 0
  const results = useStudentSearch({ q, skills }, open && searching)
  const invitesLeft = results.data?.invitesLeft ?? team.invitesLeft

  return (
    <Sheet
      open={open}
      onClose={onClose}
      title={t('screen.team.mentor.inviteTitle')}
      description={t('screen.team.mentor.inviteMeta', { n: invitesLeft, size: team.size })}
    >
      {invitesLeft === 0 && (
        <p role="status" className="rounded-md bg-warning-soft p-3 text-body-sm">
          {t('screen.team.mentor.limitReached')}
        </p>
      )}
      <TeamSearchBar
        id="studentSearch"
        label={t('screen.team.mentor.searchLabel')}
        helper={t('screen.team.mentor.searchHelper')}
        initial={q}
        onSearch={setQ}
      />
      <div className="flex flex-col gap-1.5">
        <label htmlFor="studentSkills" className="text-label">
          {t('screen.team.skillFilter')}
        </label>
        <SkillPicker id="studentSkills" value={skills} onChange={setSkills} max={20} />
      </div>
      {searching && (
        <RegionState query={results} skeleton={<Skeleton className="h-32 w-full" />}>
          {results.data && (
            <div className="flex flex-col gap-3">
              <div className="flex flex-col gap-0.5">
                <p className="text-label font-semibold" role="status">
                  {q ? t('screen.team.results', { q, n: results.data.total }) : t('screen.team.resultsFiltered', { n: results.data.total })}
                </p>
                {q && <p className="text-caption text-muted-foreground">{t('screen.team.ranking')}</p>}
              </div>
              {results.data.items.length === 0 ? (
                <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">
                  {q ? t('screen.team.mentor.noStudents', { q }) : t('screen.team.mentor.noStudentsFiltered')}
                </p>
              ) : (
                <ul className="flex flex-col gap-3" aria-label={t('screen.team.mentor.searchLabel')}>
                  {results.data.items.map((result) => (
                    <StudentResultCard
                      key={result.student.accountId}
                      result={result}
                      invitesLeft={invitesLeft}
                      full={team.size >= team.sizeLimit}
                      onView={onView}
                    />
                  ))}
                </ul>
              )}
            </div>
          )}
        </RegionState>
      )}
      <InviteByLogin invitesLeft={invitesLeft} full={team.size >= team.sizeLimit} onView={onView} />
    </Sheet>
  )
}

function InviteByLogin({ invitesLeft, full, onView }: { invitesLeft: number; full: boolean; onView: (id: string) => void }) {
  const [value, setValue] = useState('')
  const [login, setLogin] = useState('')
  const found = useStudentByLogin(login)

  function submit(event: FormEvent) {
    event.preventDefault()
    setLogin(value.trim().replace(/^@/, ''))
  }

  return (
    <section aria-labelledby="by-login-heading" className="flex flex-col gap-2 border-t border-border pt-4">
      <h3 id="by-login-heading" className="text-label font-semibold">
        {t('screen.team.mentor.byLogin')}
      </h3>
      <form onSubmit={submit} className="flex gap-2">
        <label htmlFor="inviteLogin" className="sr-only">
          {t('screen.team.mentor.loginLabel')}
        </label>
        <input
          id="inviteLogin"
          maxLength={39}
          autoComplete="off"
          placeholder={t('screen.team.mentor.loginLabel')}
          className={cn(inputClass, 'grow')}
          value={value}
          onChange={(event) => setValue(event.target.value)}
        />
        <Button type="submit" variant="secondary" disabled={!value.trim()}>
          {t('screen.team.search')}
        </Button>
      </form>
      {login && found.data && found.data.items.length === 0 && (
        <p role="status" className="text-caption text-muted-foreground">
          {t('screen.team.mentor.loginNotFound')}
        </p>
      )}
      {login && found.data && found.data.items.length > 0 && (
        <ul className="flex flex-col gap-3">
          {found.data.items.map((result) => (
            <StudentResultCard key={result.student.accountId} result={result} invitesLeft={invitesLeft} full={full} onView={onView} />
          ))}
        </ul>
      )}
    </section>
  )
}

function StudentResultCard({
  result,
  invitesLeft,
  full,
  onView,
}: {
  result: TeamStudentResult
  invitesLeft: number
  full: boolean
  onView: (id: string) => void
}) {
  const invite = useInviteStudent()
  const toasts = useToasts()
  const [error, setError] = useState<string>()
  const student = result.student
  const name = studentName(student)
  const label =
    result.relation === 'INVITED'
      ? t('screen.team.mentor.invited')
      : result.relation === 'MEMBER'
        ? t('screen.team.mentor.inTeam')
        : result.relation === 'REQUESTED'
          ? t('screen.team.mentor.requested')
          : t('screen.team.mentor.inviteButton')
  const blocked = result.relation !== 'NONE' || invitesLeft === 0 || full
  return (
    <li className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
      <div className="flex items-start gap-3">
        <Avatar name={name} />
        <div className="flex min-w-0 grow flex-col gap-1">
          <p className="flex flex-wrap items-center gap-2 text-body-sm font-semibold">
            <span className="break-words">{name}</span>
            <span className="rounded-full bg-success-soft px-2 py-0.5 text-caption text-success">{t('screen.team.verified')}</span>
            {result.relation === 'OTHER_TEAM' && (
              <span className="rounded-full bg-muted px-2 py-0.5 text-caption">{t('screen.team.mentor.otherTeam')}</span>
            )}
          </p>
          {student.school && <p className="text-caption text-muted-foreground">{student.school}</p>}
          <ul className="flex flex-wrap gap-1.5">
            {student.skills.map((skill) => (
              <li key={skill} className="rounded-full border border-border px-2 py-0.5 text-caption">
                {skill}
              </li>
            ))}
          </ul>
        </div>
      </div>
      <div className="flex flex-wrap items-center justify-end gap-2">
        {result.relation === 'OTHER_TEAM' && (
          <span className="grow text-caption text-muted-foreground">{t('screen.team.mentor.otherTeamNote', { name })}</span>
        )}
        <Button
          variant="secondary"
          size="compact"
          aria-label={t('screen.team.viewProfileOf', { name })}
          onClick={() => onView(student.accountId)}
        >
          {t('screen.team.viewProfile')}
        </Button>
        <Button
          size="compact"
          disabled={blocked || invite.isPending}
          aria-label={result.relation === 'NONE' || result.relation === 'OTHER_TEAM' ? t('screen.team.mentor.inviteButtonOf', { name }) : undefined}
          onClick={() =>
            invite.mutate(student.accountId, {
              onSuccess: () => {
                setError(undefined)
                toasts.success(t('screen.team.mentor.invitedToast', { name }))
              },
              onError: (failure) =>
                setError(failure instanceof ApiError ? problemMessage(failure, { module: 'team' }).text : problemMessage(failure).text),
            })
          }
        >
          {label}
        </Button>
      </div>
      {error && (
        <p role="alert" className="text-caption text-destructive">
          {error}
        </p>
      )}
    </li>
  )
}

/** PUB-4 in a Sheet (FR-51): the Student's public fields and their Team; never contact details. */
function StudentSheet({ studentId, onClose }: { studentId?: string; onClose: () => void }) {
  const profile = useTeamStudentProfile(studentId)
  const student = profile.data?.student
  return (
    <Sheet open={studentId !== undefined} onClose={onClose} title={student ? studentName(student) : t('screen.team.mentor.studentTitle')}>
      <RegionState query={profile} skeleton={<Skeleton className="h-48 w-full" />}>
        {profile.data && student && (
          <div className="flex flex-col gap-5">
            <div className="flex items-start gap-3">
              <Avatar name={studentName(student)} />
              <div className="flex min-w-0 flex-col gap-1">
                {student.verified && (
                  <span className="w-fit rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success">
                    {t('screen.team.verified')}
                  </span>
                )}
                <p className="text-caption text-muted-foreground">
                  {student.githubLogin && t('screen.team.mentor.studentGithub', { login: student.githubLogin })}
                  {student.school && student.yearOfStudy && (
                    <> · {t('screen.team.mentor.studentSchool', { school: student.school, year: student.yearOfStudy })}</>
                  )}
                </p>
              </div>
            </div>
            <section aria-labelledby="student-skills-heading" className="flex flex-col gap-1.5">
              <h3 id="student-skills-heading" className="text-label font-semibold">
                {t('screen.team.mentor.studentSkills')}
              </h3>
              <ul className="flex flex-wrap gap-1.5">
                {student.skills.map((skill) => (
                  <li key={skill} className="rounded-full border border-border px-2 py-0.5 text-caption">
                    {skill}
                  </li>
                ))}
              </ul>
            </section>
            <section aria-labelledby="student-team-heading" className="flex flex-col gap-1.5">
              <h3 id="student-team-heading" className="text-label font-semibold">
                {t('screen.team.mentor.studentTeamHeading')}
              </h3>
              <p className="text-body-sm">
                {profile.data.teamMentor
                  ? t('screen.team.mentor.studentTeam', {
                      mentor: profile.data.teamMentor.displayName,
                      n: profile.data.teamMentor.teamSize,
                    })
                  : t('screen.team.mentor.studentNoTeam')}
              </p>
            </section>
            {student.githubLogin && (
              <Link
                to={`/sv/${student.githubLogin}`}
                className="w-fit text-body-sm text-primary underline-offset-4 hover:underline"
              >
                {t('screen.team.mentor.publicProfile')}
              </Link>
            )}
            <p className="text-caption text-muted-foreground">{t('screen.team.mentor.publicNote')}</p>
            {student.verifiedUntil && !student.verified && (
              <p className="text-caption text-warning">
                {t('screen.team.mentor.verificationExpired', { date: formatDate(`${student.verifiedUntil}T12:00:00+07:00`) })}
              </p>
            )}
          </div>
        )}
      </RegionState>
    </Sheet>
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
