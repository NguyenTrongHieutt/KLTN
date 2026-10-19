import type { ReactNode } from 'react'
import { t, type MessageKey } from '@/i18n/t'
import type { Signals } from './recommendationApi'

/**
 * A Recommendation's signals as chips the platform writes (AD-17, UX-DR48): "Hợp với {Skills}" by
 * their current names, "Đã làm {n} công việc {type}", and "Mới". No AI footnote: nothing here is
 * generated, and nothing selects, invites, or assigns anyone.
 */
export function SignalChips({ signals, extra }: { signals: Signals; extra?: ReactNode }) {
  const chips: string[] = []
  if (signals.sharedSkills.length > 0) {
    chips.push(t('screen.matching.signals.skills', { skills: signals.sharedSkills.join(', ') }))
  }
  for (const match of signals.jobTypeMatches) {
    chips.push(
      t('screen.matching.signals.history', {
        n: match.count,
        type: t(`term.jobType${match.jobType}.label` as MessageKey),
      }),
    )
  }
  if (signals.newcomer) {
    chips.push(t('screen.matching.signals.newcomer'))
  }
  if (chips.length === 0 && !extra) {
    return null
  }
  return (
    <ul className="flex flex-wrap gap-1.5">
      {chips.map((chip) => (
        <li key={chip} className="rounded-full bg-primary-soft px-2 py-0.5 text-caption font-semibold text-primary">
          {chip}
        </li>
      ))}
      {extra}
    </ul>
  )
}
