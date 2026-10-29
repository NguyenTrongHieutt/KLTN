import { t } from '@/i18n/t'
import { formatDate } from '@/lib/dates'

/** Up to two initials for the avatar circle. */
export function initials(name: string): string {
  const words = name.replace(/^@/, '').trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) {
    return '?'
  }
  const first = words.length > 1 ? words[words.length - 2] : words[0]
  const last = words[words.length - 1]
  return (words.length > 1 ? `${first[0]}${last[0]}` : last.slice(0, 2)).toLocaleUpperCase('vi')
}

/** "{years} năm kinh nghiệm", or "Kinh nghiệm: Chưa khai" while the person has not declared it ("Tự khai"). */
export function experienceText(years: number | undefined): string {
  return years === undefined ? t('screen.person.experienceNone') : t('screen.person.experienceYears', { years })
}

/** "06/2026": the month of an instant, in Vietnam. */
export function formatMonth(instant: string): string {
  return formatDate(instant).slice(3)
}

/** The address of a person's PUB-4 (UX-DR96): their GitHub username. */
export function personPath(username: string): string {
  return `/nguoi-dung/${encodeURIComponent(username)}`
}
