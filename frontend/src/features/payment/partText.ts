import { t } from '@/i18n/t'
import { formatDate } from '@/lib/dates'
import type { BalanceLine } from './paymentApi'

/** Whether a line's split names the reader its Mentor: only such a line lists the Crew's parts. */
export function hasMentorLine(lines: BalanceLine[]): boolean {
  return lines.some((line) => line.role === 'MENTOR')
}

/** EXPERIENCE.md's final state of a split: "Đã chốt ngày {date} · {how}", or the Mentor alone's. */
export function finalText(finality: BalanceLine['finality'], finalAt: string | undefined): string | undefined {
  if (!finality || !finalAt) {
    return undefined
  }
  if (finality === 'MENTOR_ONLY') {
    return t('screen.split.state.mentorOnly')
  }
  const how =
    finality === 'CONFIRMED'
      ? t('screen.split.how.confirmed')
      : finality === 'DEADLINE'
        ? t('screen.split.how.deadline')
        : t('screen.split.how.decision')
  return t('screen.split.final', { date: formatDate(finalAt), how })
}

/** "Chờ phân chia", or "Đang chờ quản trị viên xem xét" while a Split Resolution holds it (UX-DR106). */
export function pendingText(status: BalanceLine['status']): string {
  return status === 'IN_RESOLUTION' ? t('screen.split.state.inResolution') : t('screen.payment.balance.pending')
}

/** The tone of a pending split's chip: in progress, or a problem while a Split Resolution holds it. */
export function pendingTone(status: BalanceLine['status']): string {
  return status === 'IN_RESOLUTION' ? 'bg-destructive-soft text-destructive' : 'bg-primary-soft text-primary'
}

/** A party's name as JOB-10 shows it: an erased person, or one nothing names any more. */
export function partyName(part: { name: string; erased: boolean }): string {
  return part.name || (part.erased ? t('screen.split.erased') : t('screen.split.noName'))
}
