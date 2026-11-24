import { describe, expect, it } from 'vitest'
import { formatDayMonth, formatDeadlineDay } from './dates'

describe('formatDeadlineDay', () => {
  it('reads a deadline at the end of a day in Vietnam as that day, not the next one', () => {
    // A Milestone due on 08/12: the server sends the instant 00:00 of 09/12 in Vietnam.
    expect(formatDeadlineDay('2026-12-08T17:00:00Z')).toBe('08/12')
    expect(formatDayMonth('2026-12-08T17:00:00Z')).toBe('09/12')
  })

  it.each([
    ['2026-12-08T16:59:00Z', '08/12'],
    ['2026-12-08T17:00:01Z', '09/12'],
    ['2026-12-08T17:01:00Z', '09/12'],
    ['2026-10-20T10:00:00Z', '20/10'],
  ])('leaves any other deadline on its own day: %s', (instant, day) => {
    expect(formatDeadlineDay(instant)).toBe(day)
  })
})
