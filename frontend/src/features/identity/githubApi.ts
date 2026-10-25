import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import vi from '@/i18n/vi.json'
import { apiClient, unwrap } from '@/lib/apiClient'
import { redirectTo } from '@/lib/redirect'
import { SESSION_QUERY_KEY } from './api'

export type GitHubLink = components['schemas']['GitHubLinkView']
export type GitHubLinkBlocker = components['schemas']['GitHubLinkBlockerView']
export type GitHubLinkPurpose = components['schemas']['GitHubLinkRequest']['purpose']

/** N-24's version as the SPA shows it; the backend refuses any other one (`STALE`). */
export const GITHUB_NOTICE_VERSION = (vi as { notice: { github: { version: string } } }).notice.github.version

export const GITHUB_LINK_QUERY_KEY = ['identity', 'github'] as const

/** ACC-2's GitHub row (FR-80): linked or not, re-link needed, the token kept, and what blocks "Bỏ liên kết". */
export function useGitHubLink() {
  return useQuery({
    queryKey: GITHUB_LINK_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/identity/github')),
  })
}

/**
 * "Liên kết GitHub ↗ (github.com)" after N-24: the backend records the acknowledgment and answers
 * GitHub's page, where the caller sends the browser ({@link leaveForGitHub}); GitHub returns it to
 * `returnTo`, or to ACC-2.
 */
export function useStartGitHubLink() {
  return useMutation({
    mutationFn: ({ purpose, returnTo }: { purpose: GitHubLinkPurpose; returnTo?: string }) =>
      unwrap(
        apiClient.POST('/api/v1/identity/github/link', {
          body: { noticeVersion: GITHUB_NOTICE_VERSION, purpose, returnTo },
        }),
      ),
  })
}

/** Leaves the app for GitHub's authorization page in this tab. */
export function leaveForGitHub(authorizationUrl: string): void {
  redirectTo(authorizationUrl)
}

/** "Bỏ liên kết", after its Confirmation dialog: the row and the session's GitHub username are read again. */
export function useUnlinkGitHub() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.DELETE('/api/v1/identity/github')),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: GITHUB_LINK_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY })
    },
  })
}
