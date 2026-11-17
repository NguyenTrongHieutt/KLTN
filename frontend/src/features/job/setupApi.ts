import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import vi from '@/i18n/vi.json'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Setup = components['schemas']['SetupView']

/** How often the step tracker reads the Setup while the Job is Starting (UX-DR23, UX-DR114). */
export const SETUP_POLL_MS = 3000

/**
 * N-32's version, which a self-confirmation names: the server's `vibefix.repo.self-confirmation-notice-version`,
 * mirrored in `vi.json` as the product notices are.
 */
export const SELF_CONFIRMATION_NOTICE_VERSION = (vi as { notice: { selfConfirmation: { version: string } } }).notice
  .selfConfirmation.version

export const setupKey = (jobId: string) => ['job', 'setup', jobId] as const

/**
 * JOB-H's step tracker: the Setup's steps, polled every 3 s. `settled` stops that poll once the reader has what it
 * came for: a page that shows nothing of the Setup reads it once, and the Assessment Report Page's link to the
 * read-only copy (R6, Story 23.11b) does not change again once the viewer has accepted their invitation.
 */
export function useSetup(jobId: string | undefined, settled?: (setup: Setup) => boolean) {
  return useQuery({
    queryKey: setupKey(jobId ?? ''),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/setup', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    refetchInterval: (query) =>
      settled && query.state.data !== undefined && settled(query.state.data) ? false : SETUP_POLL_MS,
    retry: false,
  })
}

/** The states in which the viewer's own row has accepted its invitation, so its link opens the repository itself. */
const ACCEPTED = ['ACCEPTED', 'TWO_FACTOR_MISSING', 'WRITE']

/** The viewer's own row: the one row the server gives a GitHub link, since nobody reads anyone else's (UX-DR23). */
export function ownRow(setup: Setup): Setup['people'][number] | undefined {
  return setup.people.find((person) => person.githubUrl)
}

/**
 * The viewer's own GitHub link and what it opens: their pending invitation while it waits for them, the repository
 * once they accepted. While the invitation is still being sent (`INVITING`) there is nothing to open yet, and a row
 * that drifted, lapsed or went leaves no link at all (R6, Story 23.11b).
 */
export function ownGitHubLink(setup: Setup): { url: string; accepted: boolean } | undefined {
  const own = ownRow(setup)
  if (!own?.githubUrl || (own.state !== 'INVITED' && !ACCEPTED.includes(own.state))) {
    return undefined
  }
  return { url: own.githubUrl, accepted: own.state !== 'INVITED' }
}

/** Nothing about the viewer's own link can change any more: they accepted it, or they have no row to accept. */
export function ownLinkSettled(setup: Setup): boolean {
  return ownRow(setup) === undefined || ownGitHubLink(setup)?.accepted === true
}

/**
 * Whether the Setup's read invitations are still out (R6, Story 23.11b): an Assessment Job reaches J7 with them
 * pending, so its tracker stays until every team row is accepted -- the only place the Mentor sees who has not.
 */
export function invitationsPending(setup: Setup): boolean {
  return setup.assessment && setup.steps.some((step) => step.key === 'INVITE' && step.state !== 'DONE')
}

/** "Thử lại" by the Customer after a block. */
export function useRetrySetup(jobId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.POST('/api/v1/jobs/{jobId}/setup/retry', { params: { path: { jobId } } })),
    onSuccess: (setup) => client.setQueryData(setupKey(jobId), setup),
  })
}

/**
 * The Customer's J7 self-confirmation (N-32, R4): the head the SHA confirmation card showed, with N-32's version. A
 * head that moved in between answers `STALE`, and the Setup then holds the new head.
 */
export function useConfirmSourceClean(jobId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (sha: string) =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/setup/confirmation', {
          params: { path: { jobId } },
          body: { sha, noticeVersion: SELF_CONFIRMATION_NOTICE_VERSION },
        }),
      ),
    onSuccess: (setup) => client.setQueryData(setupKey(jobId), setup),
  })
}
