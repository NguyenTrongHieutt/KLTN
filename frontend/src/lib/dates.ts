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
