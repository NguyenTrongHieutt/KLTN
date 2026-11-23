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
 * the only signal.
 */
export function StatusBadge({ tone, className, children }: { tone: StatusTone; className?: string; children: ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex w-fit max-w-full items-center gap-1 rounded-full px-2.5 py-0.5 text-caption font-semibold',
        TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}
