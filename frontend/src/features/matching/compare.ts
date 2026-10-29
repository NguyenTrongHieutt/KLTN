/** "So sánh" ticks at most this many bids (UX-DR101). */
export const MAX_COMPARED = 3

/** CUS-13's address for these bids, in the order chosen (UX-DR101). */
export function comparePath(jobId: string, ids: string[]): string {
  return `/cong-viec/${jobId}/ung-tuyen/so-sanh?ids=${ids.map(encodeURIComponent).join(',')}`
}

/** The bids CUS-13 compares: the IDs of `?ids=`, each once, in their order, at most {@link MAX_COMPARED}. */
export function comparedIds(param: string | null): string[] {
  return [...new Set((param ?? '').split(',').filter((id) => id !== ''))].slice(0, MAX_COMPARED)
}
