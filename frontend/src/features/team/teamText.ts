import { formatDate } from '@/lib/dates'

/** A Student's public name, or their GitHub username when they hide it (FR-51). */
export function studentName(student: { name?: string; githubLogin?: string }): string {
  return student.name ?? (student.githubLogin ? `@${student.githubLogin}` : '')
}

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

/** "06/2026": the month of an instant, in Vietnam. */
export function formatMonth(instant: string): string {
  return formatDate(instant).slice(3)
}
