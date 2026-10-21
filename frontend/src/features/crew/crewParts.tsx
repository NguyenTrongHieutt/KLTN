import { inputClass } from '@/components/formField-props'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'
import type { CrewCandidate } from './crewApi'
import type { ShareProblem } from './crewShares'

/** The Crew panel's shared pieces: the initials avatar, a share field, and the Team picker (UX-DR60). */

export function Avatar({ name }: { name: string }) {
  const letters = name
    .replace(/^@/, '')
    .trim()
    .split(/\s+/)
    .slice(-2)
    .map((word) => word.charAt(0).toUpperCase())
    .join('')
  return (
    <span aria-hidden="true" className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary-soft text-caption font-semibold text-primary">
      {letters}
    </span>
  )
}

export function ShareField({
  id,
  label,
  value,
  locked,
  problem,
  cap,
  mentor = false,
  onChange,
}: {
  id: string
  label: string
  value: string
  locked: boolean
  problem: ShareProblem | undefined
  cap: number
  mentor?: boolean
  onChange: (value: string) => void
}) {
  if (locked) {
    return <span className="font-semibold">{value === '' ? '—' : `${value}%`}</span>
  }
  const shown = value !== '' && problem !== undefined
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-1">
        <input
          id={id}
          inputMode="numeric"
          aria-label={label}
          aria-invalid={shown || undefined}
          aria-describedby={`${id}-help`}
          value={value}
          maxLength={3}
          onChange={(event) => onChange(event.target.value.replace(/\D/g, ''))}
          className={cn(inputClass, 'w-18 text-right')}
        />
        <span>%</span>
      </div>
      <span id={`${id}-help`} className={cn('text-caption', shown ? 'text-destructive' : 'text-muted-foreground')}>
        {shown
          ? t(problem === 'mentorOver' ? 'screen.crew.panel.mentorOver' : `screen.crew.panel.${problem}`, { cap })
          : mentor
            ? t('screen.crew.panel.cap', { cap })
            : ''}
      </span>
    </div>
  )
}

const CANDIDATE_TEXT: Record<CrewCandidate['state'], MessageKey> = {
  FREE: 'screen.crew.panel.free',
  IN_CREW: 'screen.crew.panel.inCrew',
  ON_CREW: 'screen.crew.panel.onCrew',
  NOT_VERIFIED: 'screen.crew.panel.notVerified',
}

/** "Thêm sinh viên": the Mentor's Team; only free, verified Students can be picked (UX-DR60). */
export function TeamPicker({
  id,
  team,
  disabled,
  onPick,
}: {
  id: string
  team: CrewCandidate[]
  disabled: boolean
  onPick: (student: CrewCandidate) => void
}) {
  return (
    <div id={id} className="flex flex-col gap-1 rounded-md border border-border bg-background p-2">
      <p className="px-2 py-1 text-caption text-muted-foreground">
        {team.length === 0 ? t('screen.crew.panel.pickEmpty') : t('screen.crew.panel.pickCount', { n: team.length })}
      </p>
      <ul aria-label={t('screen.crew.panel.pickLabel')} className="flex flex-col gap-0.5">
        {team.map((student) => (
          <li
            key={student.studentId}
            className={cn('flex items-center gap-2 rounded-md px-2 py-1.5', student.state !== 'FREE' && 'opacity-70')}
          >
            <Avatar name={student.name} />
            <span className="min-w-0 grow text-body-sm break-words">{student.name}</span>
            {student.state === 'FREE' ? (
              <>
                <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">{t('screen.crew.panel.free')}</span>
                <Button
                  type="button"
                  variant="secondary"
                  size="compact"
                  disabled={disabled}
                  aria-label={t('screen.crew.panel.addNamed', { name: student.name })}
                  onClick={() => onPick(student)}
                >
                  {t('screen.crew.panel.pickAdd')}
                </Button>
              </>
            ) : (
              <span className="text-caption text-muted-foreground">
                {student.state === 'ON_CREW' && !student.onCrewJob
                  ? t('screen.crew.panel.onCrewOther')
                  : t(CANDIDATE_TEXT[student.state], { job: student.onCrewJob ?? '' })}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
