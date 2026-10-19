/**
 * Dates as Vietnamese readers expect them, in Vietnam's time zone (Conventions, Time): the server
 * sends instants, and the client only formats them; it never computes a deadline. The pattern is
 * built from parts, because ICU's `vi-VN` separators differ between runtimes.
 */
const parts = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function fields(instant: string): Record<string, string> {
  const values: Record<string, string> = {}
  for (const part of parts.formatToParts(new Date(instant))) {
    values[part.type] = part.value
  }
  return values
}

const dated = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
})

/** "09:41". */
export function formatTime(instant: string): string {
  const { hour, minute } = fields(instant)
  return `${hour}:${minute}`
}

/** "01/10/2026": the day of an instant, in Vietnam. */
export function formatDate(instant: string): string {
  const values: Record<string, string> = {}
  for (const part of dated.formatToParts(new Date(instant))) {
    values[part.type] = part.value
  }
  return `${values.day}/${values.month}/${values.year}`
}

/** "30/09/2027": a calendar date the server sends as `YYYY-MM-DD`, with no time zone to apply. */
export function formatCalendarDate(date: string): string {
  const [year, month, day] = date.split('-')
  return `${day}/${month}/${year}`
}

/** "20/11": the day and month of a calendar date the server sends as `YYYY-MM-DD`. */
export function formatCalendarDayMonth(date: string): string {
  const [, month, day] = date.split('-')
  return `${day}/${month}`
}

/** "23/10". */
export function formatDayMonth(instant: string): string {
  const { day, month } = fields(instant)
  return `${day}/${month}`
}

/** "14:05 23/10", as vi-VN writes a time and a date. */
export function formatDayMonthTime(instant: string): string {
  const { day, month, hour, minute } = fields(instant)
  return `${hour}:${minute} ${day}/${month}`
}
