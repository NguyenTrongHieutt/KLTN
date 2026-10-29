import { BadgeCheck, Circle } from 'lucide-react'
import { t } from '@/i18n/t'
import { formatMonthYear } from '@/lib/dates'
import { cn } from '@/lib/utils'
import type { FactKind, VerifiedFact } from './personApi'

/** The fixed order of the Verified facts (UX-DR96): GitHub, then the student facts, then LinkedIn. */
const ORDER: FactKind[] = ['GITHUB', 'STUDENT', 'LINKEDIN']

/** Every kind once, in the fixed order: a kind the server left out reads "Chưa xác minh". */
function inOrder(facts: VerifiedFact[]): VerifiedFact[] {
  return ORDER.map((kind) => facts.find((fact) => fact.kind === kind) ?? { kind })
}

/** "Xác minh qua {nguồn} · {tháng/năm}", or "Chưa xác minh" in the neutral tone (UX-DR108). */
function source(fact: VerifiedFact): string {
  if (!fact.verifiedAt) {
    return t('screen.person.fact.unverified')
  }
  return t(`screen.person.fact.via${fact.kind}`, { month: formatMonthYear(fact.verifiedAt) })
}

/**
 * The Verified fact lines (UX-DR96, UX-DR108) of PUB-4, CUS-12, CUS-13 and the Bid card: one line per fact, in the fixed
 * order, each with its source and month or "Chưa xác minh". The student's school, declared name and major show with
 * "Khai khi xác minh" while the owner shows them (X9); the school email never shows. Facts are text, never a control,
 * and come from `identity` only: nothing here is derived.
 */
export function VerifiedFactLines({ facts, compact = false }: { facts: VerifiedFact[]; compact?: boolean }) {
  return (
    <ul className={cn('flex flex-col', compact ? 'gap-1' : 'gap-1.5')}>
      {inOrder(facts).map((fact) => (
        <li key={fact.kind} className="flex flex-col gap-0.5 text-body-sm">
          <span className="flex flex-wrap items-center gap-1.5">
            {fact.verifiedAt ? (
              <BadgeCheck aria-hidden="true" className="size-4 shrink-0 text-success" />
            ) : (
              <Circle aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            )}
            <span className="font-semibold">{t(`screen.person.fact.${fact.kind}`)}</span>
            {fact.kind === 'GITHUB' && fact.githubLogin && (
              <span className="break-all">{t('screen.person.fact.githubLogin', { login: fact.githubLogin })}</span>
            )}
            <span className="text-caption text-muted-foreground">{source(fact)}</span>
          </span>
          {fact.kind === 'STUDENT' && fact.verifiedAt && (fact.school || fact.name || fact.major) && (
            <span className="ps-5.5 text-caption break-words">
              {[fact.school, fact.name, fact.major].filter((part): part is string => !!part).join(' · ')}{' '}
              <span className="text-muted-foreground">({t('screen.person.fact.declared')})</span>
            </span>
          )}
        </li>
      ))}
    </ul>
  )
}

/**
 * The Profile result card's fact chips (UX-DR100): "GitHub · {tháng/năm}", LinkedIn verified or "Chưa xác minh", and
 * "Sinh viên đã xác minh" only when held.
 */
export function VerifiedFactChips({ facts }: { facts: VerifiedFact[] }) {
  const all = inOrder(facts)
  const chips: { key: string; text: string; held: boolean }[] = []
  for (const fact of all) {
    if (fact.kind === 'STUDENT') {
      if (fact.verifiedAt) {
        chips.push({ key: fact.kind, text: t('screen.person.fact.STUDENT'), held: true })
      }
      continue
    }
    chips.push({
      key: fact.kind,
      text: fact.verifiedAt
        ? t(`screen.person.chip.${fact.kind}`, { month: formatMonthYear(fact.verifiedAt) })
        : t(`screen.person.chip.${fact.kind}Unverified`),
      held: !!fact.verifiedAt,
    })
  }
  return (
    <ul className="flex flex-wrap gap-1.5">
      {chips.map((chip) => (
        <li
          key={chip.key}
          className={cn(
            'rounded-full px-2 py-0.5 text-caption',
            chip.held ? 'bg-success-soft text-success' : 'bg-muted text-foreground',
          )}
        >
          {chip.text}
        </li>
      ))}
    </ul>
  )
}
