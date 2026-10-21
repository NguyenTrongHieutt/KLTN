import { Circle, CircleAlert, CircleCheck, CircleDot, CircleX } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { useRetrySetup, useSetup, type Setup } from './setupApi'

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
 * "Tạo kho trung gian", and "Tạo kho làm việc", each done, current, pending, blocked, or failed, read every
 * 3 s. The current step is announced politely. Nobody needs to act unless a step is blocked on the
 * Customer, who then reads what to fix and may tap "Thử lại".
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
            <li key={step.key} className="flex items-center gap-2 text-body-sm">
              <Icon aria-hidden="true" className={`size-5 shrink-0 ${TONES[step.state] ?? ''}`} />
              <span className={step.state === 'PENDING' ? 'text-muted-foreground' : 'font-semibold'}>
                {t(`screen.job.setup.step${step.key}` as MessageKey)}
              </span>
              <span className="text-caption text-muted-foreground">· {t(`screen.job.setup.state${step.state}` as MessageKey)}</span>
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
        <p className="text-body-sm text-muted-foreground">{t('screen.job.setup.nothingToDo')}</p>
      )}
    </section>
  )
}
