/** The most Students a Crew holds with `features.largeCrews` on, as `job` enforces it. */
export const LARGE_CREW_LIMIT = 5

/**
 * The split of one Milestone amount, as `shared.CrewSplit` computes it on the server: each party gets
 * ⌊amount × percent / 100⌋, the remainder goes to the Mentor, and a zero part is left out.
 */
export function crewSplit(amount: number, shares: Map<string, number>, mentorId: string): Map<string, number> {
  const parts = new Map<string, number>()
  let given = 0
  for (const [party, percent] of shares) {
    const part = Math.floor((amount * percent) / 100)
    parts.set(party, part)
    given += part
  }
  parts.set(mentorId, (parts.get(mentorId) ?? 0) + amount - given)
  for (const [party, part] of parts) {
    if (part === 0) {
      parts.delete(party)
    }
  }
  return parts
}

/** Why one share field cannot be saved, or `undefined` when it can. */
export type ShareProblem = 'notWhole' | 'studentZero' | 'mentorOver'

/** A whole percentage from 0 to 100, or `undefined` for anything else. */
export function percentOf(text: string): number | undefined {
  return /^\d{1,3}$/.test(text.trim()) && Number(text) <= 100 ? Number(text) : undefined
}

/** The checks of `SHARES_INVALID` (FR-75), field by field, so the panel can say which one is wrong. */
export function shareProblem(text: string, mentor: boolean, capPercent: number): ShareProblem | undefined {
  const percent = percentOf(text)
  if (percent === undefined) {
    return 'notWhole'
  }
  if (mentor) {
    return percent > capPercent ? 'mentorOver' : undefined
  }
  return percent === 0 ? 'studentZero' : undefined
}

/** "Hùng" for "Đỗ Mạnh Hùng": Vietnamese names are called by their last word. */
export function shortName(name: string): string {
  return name.trim().split(/\s+/).at(-1) ?? name
}
