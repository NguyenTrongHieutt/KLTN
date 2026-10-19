import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { PortfolioSection, RatingLine } from './ReputationParts'
import { usePublicStudent, useStudentReputation, type PublicStudent } from './teamApi'
import { initials } from './teamText'

/**
 * PUB-4 (`/sv/{username}`, FR-51, UX-DR83): a Student's public profile, for anyone. The name, school,
 * and year only while the Student shows them, else the GitHub username; the verification status, the
 * Skills by Skill Category, the Team, the Portfolio Entries, and the Mentors' visible Ratings. Never
 * contact details or a Balance. Linked from MEN-6 and Portfolio links, never from a Customer screen.
 */
export function StudentPublicPage() {
  const { username } = useParams()
  const student = usePublicStudent(username)
  const shown = student.data ? (student.data.name ?? `@${student.data.githubLogin ?? ''}`) : undefined
  usePageTitle(shown ? t('screen.team.publicStudent.title', { name: shown }) : t('screen.team.title'))
  if (student.isError && isNotFound(student.error)) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[960px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={student} skeleton={<Skeleton className="h-96 w-full" />}>
        {student.data && username && <Profile student={student.data} username={username} />}
      </RegionState>
    </div>
  )
}

function Profile({ student, username }: { student: PublicStudent; username: string }) {
  const reputation = useStudentReputation(username)
  const name = student.name ?? `@${student.githubLogin ?? username}`
  const caption = [
    student.githubLogin && t('screen.team.publicStudent.github', { login: student.githubLogin }),
    student.school,
    student.yearOfStudy && t('screen.team.publicStudent.year', { n: student.yearOfStudy }),
  ].filter((part): part is string => !!part)
  return (
    <>
      <section className="flex flex-wrap items-start gap-4 rounded-lg border border-border bg-card p-5">
        <span
          aria-hidden="true"
          className="flex size-14 shrink-0 items-center justify-center rounded-full bg-primary-soft text-h3 text-primary"
        >
          {initials(name)}
        </span>
        <div className="flex min-w-60 grow flex-col gap-1.5">
          <div className="flex flex-wrap items-center gap-2">
            <PageHeading className="break-words">{name}</PageHeading>
            <span
              className={
                student.verified
                  ? 'rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success'
                  : 'rounded-full bg-muted px-2 py-0.5 text-caption font-semibold text-foreground'
              }
            >
              {t(student.verified ? 'screen.team.publicStudent.verified' : 'screen.team.publicStudent.unverified')}
            </span>
          </div>
          <p className="text-caption text-muted-foreground break-words">{caption.join(' · ')}</p>
          {student.skillGroups.map((group) => (
            <div key={group.category ?? 'other'} className="flex flex-wrap items-baseline gap-1.5">
              <span className="text-caption text-muted-foreground">
                {group.category ?? t('screen.team.profile.otherSkills')}:
              </span>
              {group.skills.map((skill) => (
                <span key={skill} className="rounded-full border border-border px-2.5 py-0.5 text-caption">
                  {skill}
                </span>
              ))}
            </div>
          ))}
        </div>
      </section>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
        <div className="flex min-w-0 flex-col gap-6">
          <PortfolioSection entries={reputation.data?.portfolio ?? []} headingId="student-portfolio" />
        </div>
        <aside className="flex flex-col gap-4">
          <section aria-labelledby="student-ratings" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
            <h2 id="student-ratings" className="text-h3">
              {t('screen.team.publicStudent.ratings')}
            </h2>
            {reputation.data && <RatingLine average={reputation.data.average} count={reputation.data.count} from="mentors" />}
          </section>
          <section aria-labelledby="student-team" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
            <h2 id="student-team" className="text-h3">
              {t('screen.team.publicStudent.team')}
            </h2>
            {student.team ? (
              <p className="text-body-sm">
                {student.team.mentorLogin ? (
                  <Link to={`/mentor/${student.team.mentorLogin}`} className="text-primary underline-offset-4 hover:underline">
                    {t('screen.team.publicStudent.teamOf', { mentor: student.team.mentorDisplayName })}
                  </Link>
                ) : (
                  t('screen.team.publicStudent.teamOf', { mentor: student.team.mentorDisplayName })
                )}{' '}
                <span className="text-muted-foreground">{t('screen.team.publicStudent.teamSize', { n: student.team.size })}</span>
              </p>
            ) : (
              <p className="text-body-sm text-muted-foreground">{t('screen.team.publicStudent.noTeam')}</p>
            )}
          </section>
          <p className="text-caption text-muted-foreground">{t('screen.team.publicStudent.privacy')}</p>
        </aside>
      </div>
    </>
  )
}
