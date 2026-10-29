import { Users } from 'lucide-react'
import { useId } from 'react'
import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { cn } from '@/lib/utils'
import type { MentorCrew } from './crewApi'

/** JOB-H's and JOB-6's Crew line for the Mentor: how many members signed, and the picks still waiting. */
export function CrewHeaderLine({ crew }: { crew: MentorCrew }) {
  return (
    <p className="flex items-center gap-1.5 text-body-sm">
      <Users aria-hidden="true" className="size-4 text-primary" />
      {crew.members.length > 0
        ? t('screen.crew.mentor.headerLine', { n: crew.members.length })
        : t('screen.crew.mentor.headerNone')}
      {crew.waiting > 0 && (
        <span className="text-muted-foreground">· {t('screen.crew.mentor.waiting', { n: crew.waiting })}</span>
      )}
    </p>
  )
}

/**
 * JOB-6's "Nhóm thực hiện" for the Mentor (FR-75, UX-DR102, Story 21.5): the members who signed, with their GitHub
 * usernames and join dates, which is all the Customer sees of them; no share and no remove action, since nobody who
 * signed is removed. The Crew comes from JOB-9's Crew Applications, and the confirmation waits for no member (R7).
 */
export function MentorCrewSection({ jobId, crew }: { jobId: string; crew: MentorCrew }) {
  const ids = useId()
  return (
    <section aria-labelledby={`${ids}-heading`} className="flex w-full max-w-[1120px] flex-col gap-3 self-center rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={`${ids}-heading`} className="text-h3">
          {t('screen.crew.mentor.title')}
        </h2>
        <Link to={`/cong-viec/${jobId}/tuyen-thanh-vien`} className={cn(buttonVariants({ variant: 'secondary', size: 'compact' }))}>
          {t('screen.crew.mentor.recruit')}
        </Link>
      </div>
      {crew.members.length === 0 ? (
        <p className="text-body-sm text-muted-foreground">{t('screen.crew.mentor.empty')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-border rounded-md border border-border">
          {crew.members.map((member) => (
            <li key={member.rowId} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 text-body-sm">
              <span className="flex flex-wrap items-baseline gap-2">
                <span className="font-semibold break-words">{member.name}</span>
                {member.githubLogin && <span className="font-mono text-muted-foreground">@{member.githubLogin}</span>}
              </span>
              <span className="text-muted-foreground">{t('screen.crew.mentor.joined', { date: formatDayMonth(member.joinedAt) })}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="text-caption text-muted-foreground">{t('screen.crew.mentor.note')}</p>
    </section>
  )
}
