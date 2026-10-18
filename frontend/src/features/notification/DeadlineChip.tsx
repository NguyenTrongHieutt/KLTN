import { Clock } from 'lucide-react'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { cn } from '@/lib/utils'

/**
 * The Deadline chip (DESIGN.md, EXPERIENCE.md): the absolute date, warning within 48 h of a deadline
 * that decides automatically. Whether it is near comes from the server.
 */
export function DeadlineChip({ deadlineAt, near }: { deadlineAt: string; near: boolean }) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-caption',
        near ? 'bg-warning-soft text-warning' : 'bg-muted text-muted-foreground',
      )}
    >
      <Clock aria-hidden="true" className="size-3.5" />
      {t('screen.shared.todo.deadline', { date: formatDayMonth(deadlineAt) })}
    </span>
  )
}
