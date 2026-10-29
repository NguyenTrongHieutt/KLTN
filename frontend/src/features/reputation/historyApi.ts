import { useQueries, useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type PersonReputation = components['schemas']['PersonReputationView']
export type RoleReputation = components['schemas']['RoleReputationView']
export type ReputationHistory = components['schemas']['ReputationHistoryView']
export type RoleSummary = components['schemas']['RoleSummaryView']
export type PortfolioEntry = components['schemas']['PortfolioEntry']

/** The server's page of histories in short (SG-43). */
export const MAX_HISTORIES = 20

/**
 * PUB-4's two histories (FR-50, FR-51, Story 21.3), for anyone: "Khi làm người hướng dẫn" and "Khi làm thành viên
 * nhóm", each with its Jobs, its Ratings, and its Portfolio Entries.
 */
export function usePersonReputation(username: string | undefined) {
  return useQuery({
    queryKey: ['reputation', 'person', username],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/users/{username}/reputation', { params: { path: { username: username ?? '' } } })),
    enabled: username !== undefined,
    retry: false,
  })
}

/**
 * The Bid cards' histories in short, with the state of their reads for `RegionState`: pending until every page
 * answered, in error when one failed.
 */
export interface Histories {
  byUsername: Map<string, ReputationHistory>
  isPending: boolean
  isError: boolean
  error: unknown
  refetch: () => void
}

/**
 * The Bid cards' histories in short, by GitHub username, a page of 20 per call and never one request per card
 * (SG-43); an unknown username has none.
 */
export function useReputationHistories(usernames: string[]): Histories {
  const asked = [...new Set(usernames)]
  const pages: string[][] = []
  for (let start = 0; start < asked.length; start += MAX_HISTORIES) {
    pages.push(asked.slice(start, start + MAX_HISTORIES))
  }
  const results = useQueries({
    queries: pages.map((page) => ({
      queryKey: ['reputation', 'histories', page],
      queryFn: () => unwrap(apiClient.GET('/api/v1/reputation/histories', { params: { query: { username: page } } })),
    })),
  })
  const byUsername = new Map<string, ReputationHistory>()
  results.forEach((result) => (result.data ?? []).forEach((history) => byUsername.set(history.username, history)))
  const failed = results.find((result) => result.isError)
  return {
    byUsername,
    isPending: results.some((result) => result.isPending),
    isError: failed !== undefined,
    error: failed?.error,
    refetch: () => results.forEach((result) => void result.refetch()),
  }
}
