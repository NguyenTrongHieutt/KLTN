/**
 * The split of one Milestone amount, as `shared.CrewSplit` computes it on the server: each party gets
 * ⌊amount × percent / 100⌋, the remainder goes to the Mentor, and a zero part is left out. The share Team Issues still
 * read the pinned Crew Terms version until Story 22.1's Milestone Splits (moved here from the retired Crew panel).
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
