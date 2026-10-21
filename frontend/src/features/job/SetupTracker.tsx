import { Circle, CircleAlert, CircleCheck, CircleDot, CircleX, ExternalLink } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { useRetrySetup, useSetup, type Setup } from './setupApi'

type Person = Setup['people'][number]

/** GitHub's own page for turning on two-factor authentication. */
const TWO_FACTOR_SETTINGS = 'https://github.com/settings/security'

/**
 * The viewer's own pending invitation: only their own sub-row carries a GitHub link. GitHub greys out
 * "Accept invitation" until two-factor authentication is on, and no API reports it before acceptance
 * (Spike S4, 2026-10-22), so that row always says 2FA comes first.
 */
function ownInvited(person: Person): boolean {
  return person.state === 'INVITED' && !!person.githubUrl
}

const ICONS = {
  DONE: CircleCheck,
  CURRENT: CircleDot,
  PENDING: Circle,
  BLOCKED: CircleAlert,
  FAILED: CircleX,
} as const

const TONES: Record<string, string> = {
  DONE: 'text-success',
  CURRENT: 'text-primary',
  PENDING: 'text-muted-foreground',
  BLOCKED: 'text-warning',
  FAILED: 'text-destructive',
}

/**
 * JOB-H's step tracker while a Code Job is Starting (UX-DR23, UX-DR25, AD-11): "Kiểm tra lại dự án gốc",
 * "Tạo kho trung gian", "Tạo kho làm việc", "Mời người hướng dẫn và nhóm trên GitHub" with a sub-row per
 * person, and "Cấp quyền ghi và kiểm tra", each done, current, pending, blocked, or failed, read every 3 s.
 * The current step is announced politely. The Customer sees the Mentor's name and the Crew's GitHub
 * usernames; each invitee sees a GitHub link on their own sub-row, and while invited, that two-factor
 * authentication comes first. Nobody else needs to act unless a step is blocked on them: the Customer then
 * reads what to fix and may tap "Thử lại".
 */
export function SetupTracker({ jobId }: { jobId: string }) {
  const setup = useSetup(jobId)
  if (!setup.data) {
    return null
  }
  return <Steps setup={setup.data} />
}

function Steps({ setup }: { setup: Setup }) {
  const retry = useRetrySetup(setup.jobId)
  const current = setup.steps.find((step) => step.state !== 'DONE')
  const announcement = current
    ? t('screen.job.setup.announce', {
        step: t(`screen.job.setup.step${current.key}` as MessageKey),
        state: t(`screen.job.setup.state${current.state}` as MessageKey),
      })
    : t('screen.job.setup.allDone')
  return (
    <section aria-labelledby="job-setup" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <h2 id="job-setup" className="text-h3">
        {t('screen.job.setup.title')}
      </h2>
      <ol className="flex flex-col gap-2">
        {setup.steps.map((step) => {
          const Icon = ICONS[step.state as keyof typeof ICONS] ?? Circle
          return (
            <li key={step.key} className="flex flex-wrap items-center gap-2 text-body-sm">
              <Icon aria-hidden="true" className={`size-5 shrink-0 ${TONES[step.state] ?? ''}`} />
              <span className={step.state === 'PENDING' ? 'text-muted-foreground' : 'font-semibold'}>
                {t(`screen.job.setup.step${step.key}` as MessageKey)}
              </span>
              <span className="text-caption text-muted-foreground">· {t(`screen.job.setup.state${step.state}` as MessageKey)}</span>
              {step.key === 'INVITE' && setup.people.length > 0 && <People people={setup.people} />}
            </li>
          )
        })}
      </ol>
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>
      {setup.status === 'BLOCKED' ? (
        setup.canRetry ? (
          <div className="flex flex-col gap-2">
            <p className="text-body-sm">
              {t(`screen.job.setup.blocked${setup.blockedReason ?? 'Other'}` as MessageKey, { n: setup.openFindings ?? 0 })}
            </p>
            <Button className="w-fit" disabled={retry.isPending} onClick={() => retry.mutate()}>
              {t('screen.job.setup.retry')}
            </Button>
          </div>
        ) : (
          <p className="text-body-sm">{t('screen.job.setup.blockedOther')}</p>
        )
      ) : setup.status === 'FAILED' ? (
        <p className="text-body-sm">{t('screen.job.setup.failed')}</p>
      ) : (
        !setup.people.some(ownInvited) && (
          <p className="text-body-sm text-muted-foreground">{t('screen.job.setup.nothingToDo')}</p>
        )
      )}
    </section>
  )
}

/** UX-DR23's sub-rows: one per person, with their state and, on their own row, the GitHub link. */
function People({ people }: { people: Person[] }) {
  return (
    <ul className="flex w-full flex-col gap-1 pl-7">
      {people.map((person, index) => (
        <li key={`${person.role}-${index}`} className="flex flex-wrap items-center gap-1.5 text-caption">
          <span className="font-semibold break-words">{person.name}</span>
          <span className="text-muted-foreground">
            ·{' '}
            {t(`screen.job.setup.person${person.state}` as MessageKey, {
              date: person.expiresAt ? formatDayMonth(person.expiresAt) : '',
            })}
          </span>
          {person.githubUrl && (
            <a
              href={person.githubUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
            >
              {t('screen.job.setup.openGitHub')}
              <ExternalLink aria-hidden="true" className="size-3.5" />
            </a>
          )}
          {ownInvited(person) && (
            <p className="flex w-full flex-wrap items-center gap-1.5">
              <span>{t('screen.job.setup.ownInvitedHint')}</span>
              <a
                href={TWO_FACTOR_SETTINGS}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
              >
                {t('screen.job.setup.enableTwoFactor')}
                <ExternalLink aria-hidden="true" className="size-3.5" />
              </a>
              <span className="text-muted-foreground">{t('screen.job.setup.githubDomain')}</span>
            </p>
          )}
        </li>
      ))}
    </ul>
  )
}
