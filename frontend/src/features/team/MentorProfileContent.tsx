import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { useRequestToJoin, type PublicMentor } from './teamApi'
import { formatMonth, initials } from './teamText'

/**
 * PUB-5's content (FR-51, UX-DR65), on its page and in STU-6's Sheet: the display name, the
 * experience, the years, the Skills by Skill Category, the Team with its listed Students, and for a
 * signed-in Student the join request. Portfolio Entries and Ratings arrive with Story 3.5.
 */
export function MentorProfileContent({
  mentor,
  visitor,
  onPage = false,
}: {
  mentor: PublicMentor
  visitor: boolean
  /** On PUB-5's own page the name is the page's heading; in a Sheet, the Sheet's title names it. */
  onPage?: boolean
}) {
  return (
    <div className="flex flex-col gap-6">
      <section className="flex flex-wrap items-start gap-4">
        <span
          aria-hidden="true"
          className="flex size-14 shrink-0 items-center justify-center rounded-full bg-primary-soft text-h3 text-primary"
        >
          {initials(mentor.displayName)}
        </span>
        <div className="flex min-w-0 grow flex-col gap-1">
          {onPage ? (
            <PageHeading className="break-words">{mentor.displayName}</PageHeading>
          ) : (
            <p className="text-h3 break-words">{mentor.displayName}</p>
          )}
          <p className="w-fit rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success">
            {t('screen.team.profile.approved')}
          </p>
          <p className="text-caption text-muted-foreground">
            {t('screen.team.profile.meta', { years: mentor.yearsOfExperience, month: formatMonth(mentor.memberSince) })}
            {mentor.githubLogin && <> · @{mentor.githubLogin}</>}
          </p>
          <p className="text-label">{t('screen.team.profile.teamSize', { n: mentor.teamSize })}</p>
        </div>
      </section>
      <p className="text-body-sm break-words whitespace-pre-line">{mentor.experience}</p>
      <JoinArea mentor={mentor} visitor={visitor} />
      <section aria-labelledby="mentor-skills-heading" className="flex flex-col gap-3">
        <h3 id="mentor-skills-heading" className="text-h3">
          {t('screen.team.profile.skills')}
        </h3>
        {mentor.skillGroups.map((group) => (
          <div key={group.category ?? 'other'} className="flex flex-col gap-1.5">
            <p className="text-caption text-muted-foreground">{group.category ?? t('screen.team.profile.otherSkills')}</p>
            <ul className="flex flex-wrap gap-1.5">
              {group.skills.map((skill) => (
                <li key={skill} className="rounded-full border border-border px-2.5 py-0.5 text-caption">
                  {skill}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </section>
      <section aria-labelledby="mentor-team-heading" className="flex flex-col gap-2">
        <h3 id="mentor-team-heading" className="text-h3">
          {t('screen.team.profile.team')}
        </h3>
        <p className="text-caption text-muted-foreground">
          {t('screen.team.profile.teamLine', { n: mentor.teamSize, listed: mentor.listedMembers.length })}
        </p>
        {mentor.listedMembers.length > 0 && (
          <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
            {mentor.listedMembers.map((member, index) => (
              <li key={`${member.name}-${index}`} className="flex flex-wrap items-center gap-3 px-4 py-2.5">
                <span
                  aria-hidden="true"
                  className="flex size-8 shrink-0 items-center justify-center rounded-full bg-muted text-caption"
                >
                  {initials(member.name)}
                </span>
                <span className="flex min-w-0 grow flex-col">
                  <span className="text-body-sm font-semibold break-words">{member.name}</span>
                  {member.school && <span className="text-caption text-muted-foreground">{member.school}</span>}
                </span>
                {member.verified && (
                  <span className="rounded-full bg-success-soft px-2 py-0.5 text-caption text-success">
                    {t('screen.team.verified')}
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/** "Xin vào nhóm" with the reason it is disabled, or what a visitor and an unavailable Mentor read. */
function JoinArea({ mentor, visitor }: { mentor: PublicMentor; visitor: boolean }) {
  const request = useRequestToJoin()
  const toasts = useToasts()
  const viewer = mentor.viewer
  if (!mentor.available) {
    return (
      <p role="status" className="rounded-md bg-muted p-3 text-body-sm">
        {t('screen.team.profile.unavailable')}
      </p>
    )
  }
  if (!viewer) {
    return visitor ? <p className="text-body-sm text-muted-foreground">{t('screen.team.profile.visitorJoin')}</p> : null
  }
  if (viewer.relation === 'MEMBER') {
    return <p className="text-body-sm">{t('screen.team.profile.yourTeam')}</p>
  }
  if (viewer.relation === 'INVITED') {
    return (
      <p className="text-body-sm">
        {t('screen.team.profile.invitedYou')}{' '}
        <Link to="/nhom" className="text-primary underline-offset-4 hover:underline">
          {t('nav.team')}
        </Link>
      </p>
    )
  }
  if (viewer.relation === 'REQUESTED' && viewer.pending) {
    return (
      <div className="flex flex-col items-start gap-1.5">
        <Button disabled>{t('screen.team.profile.requestSent')}</Button>
        <p className="text-caption text-muted-foreground">
          {t('screen.team.profile.sentLine', {
            date: formatDayMonth(viewer.pending.createdAt),
            lapse: formatDayMonth(viewer.pending.lapsesAt),
          })}
        </p>
      </div>
    )
  }
  const reason = viewer.inTeam
    ? t('screen.team.profile.inTeam')
    : !viewer.verified
      ? t('screen.team.profile.unverified')
      : mentor.teamSize >= mentor.sizeLimit
        ? t('screen.team.profile.teamFull')
        : viewer.requestsLeft === 0
          ? t('screen.team.profile.noRequestsLeft')
          : undefined
  return (
    <div className="flex flex-col items-start gap-1.5">
      <Button
        disabled={reason !== undefined || request.isPending}
        aria-describedby="join-reason"
        onClick={() =>
          request.mutate(mentor.accountId, {
            onSuccess: () => toasts.success(t('screen.team.profile.requestToast')),
            onError: (error) => toasts.error(problemMessage(error, { module: 'team' }).text),
          })
        }
      >
        {t('screen.team.profile.join')}
      </Button>
      <p id="join-reason" className="text-caption text-muted-foreground">
        {reason ?? `${t('screen.team.profile.joinHint')} ${t('screen.team.profile.requestsLeft', { n: viewer.requestsLeft })}`}
      </p>
    </div>
  )
}
