import type { ReactNode } from 'react'
import { OutLink } from '@/features/product/WorkflowSetup'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'

/** A GitHub Actions run as its row shows it: waiting for GitHub, running, done, or not run. */
export type RunState = 'WAITING' | 'RUNNING' | 'DONE' | 'FAILED'

const TONES: Record<RunState, string> = {
  WAITING: 'bg-muted text-foreground',
  RUNNING: 'bg-primary-soft text-primary',
  DONE: 'bg-success-soft text-success',
  FAILED: 'bg-destructive-soft text-destructive',
}

/**
 * The Actions run status row (EXPERIENCE.md, AD-44, AD-46, Story 23.6c): one line with the run's purpose, its state as a
 * Status badge ("Đang chờ GitHub", "Đang chạy", "Xong", "Không chạy được"), and the GitHub run link "Xem lần chạy ↗
 * (github.com)" when the surface passes one; below it, a run that did not run names its reason, with the one way
 * forward the surface offers. The surface decides who sees the link (the Customer, for runs in their own repository
 * only) and polls with its own interval.
 * @param htmlUrl the run's page on GitHub; shown only as an https link
 * @param reason the plain reason a failed run shows, already worded for the viewer's role
 * @param action the one way forward, such as "Thử lại"; none for those who cannot act
 */
export function ActionsRunRow({
  purpose,
  state,
  htmlUrl,
  reason,
  action,
}: {
  purpose: string
  state: RunState
  htmlUrl?: string
  reason?: string
  action?: ReactNode
}) {
  return (
    <div
      role="group"
      aria-label={purpose}
      className="flex w-full flex-col gap-2 rounded-md border border-border bg-background p-3"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-body-sm font-semibold break-words">{purpose}</span>
        <span className={cn('w-fit rounded-full px-2 py-0.5 text-caption font-semibold', TONES[state])}>
          <span className="sr-only">{t('screen.job.actionsRun.statePrefix')} </span>
          {t(`screen.job.actionsRun.state${state}` as MessageKey)}
        </span>
        {htmlUrl?.startsWith('https://') && <OutLink href={htmlUrl}>{t('screen.job.actionsRun.open')}</OutLink>}
      </div>
      {reason && <p className="text-body-sm break-words">{reason}</p>}
      {action}
    </div>
  )
}
