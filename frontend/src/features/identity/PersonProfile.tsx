import { useId } from 'react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { ReportUserMenu } from '@/features/abuse/ReportAbuseMenu'
import { OutsideLink } from '@/features/delivery/OutsideLink'
import type { HeadingLevel } from '@/features/reputation/headingLevel'
import { usePersonReputation } from '@/features/reputation/historyApi'
import { LevelHeading } from '@/features/reputation/LevelHeading'
import { RoleHistories } from '@/features/reputation/RoleHistories'
import { t } from '@/i18n/t'
import { formatMonthYear } from '@/lib/dates'
import { usePublicPerson, type PublicPerson } from './personApi'
import { experienceText, initials } from './personText'
import { VerifiedFactLines } from './VerifiedFacts'

/** A typed link's host, shown beside it; a link that is not a URL shows whole. */
function domainOf(url: string): string {
  try {
    return new URL(url).hostname
  } catch {
    return url
  }
}

/**
 * PUB-4's content (UX-DR96, Story 21.3), on its page and in the Sheets of CUS-8, CUS-12 and the people search: the
 * SPA composes it from `identity`'s read and `reputation`'s (AD-3). The display name, the Verified facts in their fixed
 * order, the typed profile marked "Tự khai", the Skills by Skill Category, the month joined, and the two histories.
 * Never contact details, a Balance, the school email, or private feedback; a locked person shows only that they are not
 * active; on one's own page, "Đây là hồ sơ người khác thấy" with "Sửa hồ sơ".
 */
export function PersonProfileContent({
  username,
  onPage = false,
  level = 2,
}: {
  username: string
  onPage?: boolean
  /** Its sections' heading level: 2 on PUB-4's page, deeper inside a Sheet and its sections. */
  level?: HeadingLevel
}) {
  const person = usePublicPerson(username)
  return (
    <RegionState query={person} skeleton={<Skeleton className="h-96 w-full" />}>
      {person.data &&
        (person.data.locked ? (
          <p role="status" className="rounded-md bg-muted p-4 text-body-sm">
            {t('screen.person.locked')}
          </p>
        ) : (
          <Person person={person.data} username={username} onPage={onPage} level={level} />
        ))}
    </RegionState>
  )
}

function Person({
  person,
  username,
  onPage,
  level,
}: {
  person: PublicPerson
  username: string
  onPage: boolean
  level: HeadingLevel
}) {
  const reputation = usePersonReputation(username)
  const ids = useId()
  const name = person.displayName ?? `@${username}`
  return (
    <div className="flex flex-col gap-6">
      {person.own && (
        <div role="note" className="flex flex-wrap items-center justify-between gap-3 rounded-md bg-primary-soft p-3 text-body-sm">
          <span>{t('screen.person.own')}</span>
          <Link to="/ho-so" className="font-semibold text-primary underline-offset-4 hover:underline">
            {t('screen.person.edit')}
          </Link>
        </div>
      )}
      <section className="flex flex-wrap items-start gap-4">
        <span
          aria-hidden="true"
          className="flex size-14 shrink-0 items-center justify-center rounded-full bg-primary-soft text-h3 text-primary"
        >
          {initials(name)}
        </span>
        <div className="flex min-w-0 grow flex-col gap-1">
          {onPage ? (
            <div className="flex flex-wrap items-center gap-2">
              <PageHeading className="break-words">{name}</PageHeading>
              {!person.own && <ReportUserMenu login={person.githubLogin ?? username} name={name} />}
            </div>
          ) : (
            <p className="text-h3 break-words">{name}</p>
          )}
          {person.memberSince && (
            <p className="text-caption text-muted-foreground">
              {t('screen.person.joined', { month: formatMonthYear(person.memberSince) })}
            </p>
          )}
        </div>
      </section>
      <section aria-labelledby={`${ids}-verified`} className="flex flex-col gap-2">
        <LevelHeading level={level} id={`${ids}-verified`} className="text-h3">
          {t('screen.person.verifiedHeading')}
        </LevelHeading>
        <VerifiedFactLines facts={person.verified} />
      </section>
      <section aria-labelledby={`${ids}-typed`} className="flex flex-col gap-2">
        <div className="flex flex-wrap items-baseline gap-2">
          <LevelHeading level={level} id={`${ids}-typed`} className="text-h3">
            {t('screen.person.typedHeading')}
          </LevelHeading>
          <span className="rounded-full bg-muted px-2 py-0.5 text-caption">{t('screen.person.selfDeclared')}</span>
        </div>
        <p className="text-body-sm">{experienceText(person.yearsOfExperience)}</p>
        {person.experience && <p className="text-body-sm break-words whitespace-pre-line">{person.experience}</p>}
        {person.links.length > 0 && (
          <div className="flex flex-col gap-1">
            <p className="text-caption text-muted-foreground">{t('screen.person.links')}</p>
            <ul className="flex flex-col gap-1.5">
              {person.links.map((link) => (
                <li key={link} className="text-body-sm break-all">
                  <OutsideLink url={link} domain={domainOf(link)} label={link} />
                </li>
              ))}
            </ul>
          </div>
        )}
        {person.repositories.length > 0 && (
          <div className="flex flex-col gap-1">
            <p className="text-caption text-muted-foreground">{t('screen.person.repositories')}</p>
            <ul className="flex flex-col gap-1.5">
              {person.repositories.map((repository) => (
                <li key={repository} className="text-body-sm break-all">
                  <OutsideLink url={`https://github.com/${repository}`} domain="github.com" label={repository} />
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>
      <section aria-labelledby={`${ids}-skills`} className="flex flex-col gap-3">
        <LevelHeading level={level} id={`${ids}-skills`} className="text-h3">
          {t('screen.person.skills')}
        </LevelHeading>
        {person.skillGroups.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.person.noSkills')}</p>
        ) : (
          person.skillGroups.map((group) => (
            <div key={group.category ?? 'other'} className="flex flex-col gap-1.5">
              <p className="text-caption text-muted-foreground">{group.category ?? t('screen.person.otherSkills')}</p>
              <ul className="flex flex-wrap gap-1.5">
                {group.skills.map((skill) => (
                  <li key={skill} className="rounded-full border border-border px-2.5 py-0.5 text-caption">
                    {skill}
                  </li>
                ))}
              </ul>
            </div>
          ))
        )}
      </section>
      <RegionState query={reputation} skeleton={<Skeleton className="h-40 w-full" />}>
        {reputation.data && <RoleHistories reputation={reputation.data} level={level} />}
      </RegionState>
      <p className="text-caption text-muted-foreground">{t('screen.person.privacy')}</p>
    </div>
  )
}
