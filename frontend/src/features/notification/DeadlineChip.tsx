import { Clock } from 'lucide-react'
import { t } from '@/i18n/t'
import { formatDeadlineDay } from '@/lib/dates'
import { cn } from '@/lib/utils'

/**
 * The Deadline chip (DESIGN.md, EXPERIENCE.md): the absolute date, warning within 48 h of a deadline
 * that decides automatically. Whether it is near comes from the server.
 * @param text the chip's own wording, such as the closing choice's "Chọn trước {date}"; else "Hạn {date}"
 */
export function DeadlineChip({ deadlineAt, near, text }: { deadlineAt: string; near: boolean; text?: string }) {
  return (
    <span
      className={cn(
        // `w-fit`: as a column's own item it would otherwise stretch into a bar as wide as the card.
        'inline-flex w-fit shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-caption',
        near ? 'bg-warning-soft text-warning' : 'bg-muted text-muted-foreground',
      )}
    >
      <Clock aria-hidden="true" className="size-3.5" />
      {text ?? t('screen.shared.todo.deadline', { date: formatDeadlineDay(deadlineAt) })}
    </span>
  )
}
