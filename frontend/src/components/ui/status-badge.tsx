import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * The five tones of DESIGN.md's Status badge (`status-badge-*`). Each has one meaning everywhere:
 * - `waiting`: waiting on you (a decision, a deadline, a warning);
 * - `progress`: in progress;
 * - `done`: done or safe (accepted, verified, paid);
 * - `problem`: blocked or harmful;
 * - `neutral`: anything else, such as a draft or a cancelled Job.
 */
export type StatusTone = 'waiting' | 'progress' | 'done' | 'problem' | 'neutral'

const TONES: Record<StatusTone, string> = {
  waiting: 'bg-warning-soft text-warning',
  progress: 'bg-info-soft text-info',
  done: 'bg-success-soft text-success',
  problem: 'bg-destructive-soft text-destructive',
  neutral: 'bg-muted text-muted-foreground',
}

/**
 * A read-only status pill in `{typography.caption}` at 600 weight (DESIGN.md, Status badge). The pill shape means
 * "status, read-only": never use it for something clickable. The text always names the status, so color is never
 * the only signal. It never breaks inside: a status is a few words.
 */
export function StatusBadge({
  tone,
  role,
  className,
  children,
}: {
  tone: StatusTone
  /** "status" where the badge changes in place and the change should be announced. */
  role?: 'status'
  className?: string
  children: ReactNode
}) {
  return (
    <span
      role={role}
      className={cn(
        'inline-flex w-fit max-w-full items-center gap-1 rounded-full px-2.5 py-0.5 text-caption font-semibold whitespace-nowrap',
        TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}
