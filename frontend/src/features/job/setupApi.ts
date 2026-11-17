import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import vi from '@/i18n/vi.json'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Setup = components['schemas']['SetupView']

/** How often the step tracker reads the Setup while the Job is Starting and its steps run (UX-DR23, UX-DR114). */
export const SETUP_POLL_MS = 3000

/**
 * How often it is read once the Job has left Starting (R6, Story 23.11b): an Assessment Job starts with its read
 * invitations still out, so what is left to watch is people accepting on GitHub, over hours or days rather than
 * seconds. The Job's own progress polls at this rate too.
 */
export const SETUP_WAITING_POLL_MS = 30_000

/**
 * N-32's version, which a self-confirmation names: the server's `vibefix.repo.self-confirmation-notice-version`,
 * mirrored in `vi.json` as the product notices are.
 */
export const SELF_CONFIRMATION_NOTICE_VERSION = (vi as { notice: { selfConfirmation: { version: string } } }).notice
  .selfConfirmation.version

export const setupKey = (jobId: string) => ['job', 'setup', jobId] as const

/**
 * JOB-H's step tracker: the Setup's steps, polled every 3 s while the Job is Starting and every 30 s after that, since
 * a Job that already started is only waiting for people (R6, Story 23.11b). `settled` stops the poll altogether once
 * the reader can learn nothing more: a page that shows nothing of the Setup reads it once, and JOB-1's link to the
 * read-only copy cannot change again after the viewer accepted their invitation.
 */
export function useSetup(jobId: string | undefined, settled?: (setup: Setup) => boolean) {
  return useQuery({
    queryKey: setupKey(jobId ?? ''),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/setup', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    refetchInterval: (query) => {
      const setup = query.state.data
      if (setup === undefined) {
        return SETUP_POLL_MS
      }
      return settled?.(setup) ? false : setup.jobState === 'STARTING' ? SETUP_POLL_MS : SETUP_WAITING_POLL_MS
    },
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
 * that drifted, lapsed or was revoked leaves no link at all (R6, Story 23.11b).
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

/**
 * Nothing the step tracker shows can come back on this Job (R6, Story 23.11b), so it may stop reading: a Code Job past
 * Starting, or any Job past Active. An Active Assessment Job goes on being read, at the slower rate, because a Crew
 * change invites somebody new to the copy and the tracker has to come back with their row.
 */
export function trackerSettled(setup: Setup): boolean {
  return setup.jobState !== 'STARTING' && !(setup.assessment && setup.jobState === 'ACTIVE')
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
