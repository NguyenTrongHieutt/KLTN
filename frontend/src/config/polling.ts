/**
 * Every polling interval of the SPA (AD-27, UX-DR25). Polls pause while the tab is hidden (TanStack
 * Query's default), refresh on window focus, and back off after errors, doubling up to
 * {@link MAX_BACKOFF_MS}. A successful command refreshes the `notification` queries at once
 * (`app/queryClient.ts`). There are no WebSocket or server-sent events.
 */
export const POLLING = {
  /** To-dos (strip, CUS-1, MEN-1, STU-1) and the unread notification count. */
  todos: 60_000,
  unreadCount: 60_000,
  /** ADM-1's queue counts and alert list (UX-DR69). */
  adminOverview: 60_000,
} as const

export const MAX_BACKOFF_MS = 5 * 60_000

/** What polling reads of a TanStack Query query, whatever its data type. */
interface PolledQuery {
  state: { dataUpdateCount: number; errorUpdateCount: number; status: string }
}

/** For each query, the error count when its data last changed: what came after are failures in a row. */
const sinceData = new WeakMap<PolledQuery, { dataUpdateCount: number; errorsBefore: number }>()

/** How many fetches of `query` failed in a row since its last success. */
export function failuresInARow(query: PolledQuery): number {
  const { dataUpdateCount, errorUpdateCount, status } = query.state
  let mark = sinceData.get(query)
  if (!mark || mark.dataUpdateCount !== dataUpdateCount) {
    mark = { dataUpdateCount, errorsBefore: status === 'error' ? errorUpdateCount - 1 : errorUpdateCount }
    sinceData.set(query, mark)
  }
  return status === 'error' ? errorUpdateCount - mark.errorsBefore : 0
}

/**
 * A `refetchInterval` for a polled query: `base` while it succeeds, then twice as long after each
 * failure in a row, never longer than 5 minutes.
 */
export function pollEvery(base: number) {
  return (query: PolledQuery) => {
    const failures = failuresInARow(query)
    return failures > 0 ? Math.min(base * 2 ** failures, MAX_BACKOFF_MS) : base
  }
}

/** Marks a query as polled, so its failures show the offline line (`app/usePollHealth.ts`). */
export const POLLED = { poll: true } as const
