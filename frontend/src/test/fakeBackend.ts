import { vi } from 'vitest'
import type { Capabilities, Session } from '@/features/identity/api'

type Handler = (request: Request) => Response | Promise<Response>

export const anonymous: Session = { status: 'ANONYMOUS', privacyNoticeVersion: '3.1' }

/** A Google account on PUB-3, waiting to accept N-10 (Story 19.1). */
export const welcome: Session = { status: 'SIGNING_UP', displayName: 'Nguyễn Thị Lan', privacyNoticeVersion: '3.1' }

/** What an account can show for itself (Story 20.1): by default nothing verified, and unlocked. */
export function capabilities(overrides: Partial<Capabilities> = {}): Capabilities {
  return { githubVerified: false, linkedinVerified: false, studentVerified: false, locked: false, ...overrides }
}

/** A signed-in Google account: a `USER` with no role and nothing verified yet (Stories 19.1, 20.1). */
export const signedInWithGoogle: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '3.1',
  displayName: 'Nguyễn Thị Lan',
  account: { id: '00000000-0000-7000-8000-000000000002', kind: 'USER', capabilities: capabilities(), theme: 'LIGHT' },
}

/**
 * A signed-in `USER` with GitHub linked. No account has a role (Story 20.6): a test gives the user their role in
 * the Jobs it opens with {@link inEveryJob}, and their other relations with the handlers it answers.
 */
export const signedInUser: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  githubLogin: 'octo-cat',
  account: {
    id: '00000000-0000-7000-8000-000000000001',
    kind: 'USER',
    capabilities: capabilities({ githubVerified: true }),
    theme: 'LIGHT',
  },
}

/** A signed-in Administrator's account, told by its kind (Story 20.1), without the Administrator session view. */
export const administratorAccount: NonNullable<Session['account']> = {
  id: '00000000-0000-7000-8000-00000000a001',
  kind: 'ADMINISTRATOR',
  capabilities: capabilities(),
  theme: 'LIGHT',
}

/**
 * The user's role in every Job the test opens (AD-43, Story 20.6): what `GET /api/v1/jobs/{jobId}/role` answers,
 * which picks each Job screen's variant and JOB-H's "Bạn là: …". A Crew member is `ACTIVE` unless `crewKind` says
 * otherwise. Spread it into {@link fakeBackend}'s handlers; without it, every Job answers `NOT_FOUND`.
 */
export function inEveryJob(
  role: 'CUSTOMER' | 'MENTOR' | 'CREW',
  crewKind: 'ASSIGNED' | 'ACTIVE' | 'FORMER' = 'ACTIVE',
): Record<string, Handler> {
  return { 'GET /api/v1/jobs/*/role': () => json(role === 'CREW' ? { role, crewKind } : { role }) }
}

/** One Job of "Công việc" with the user's role in it, as `GET /api/v1/my-jobs` lists it (Story 20.6). */
export interface HeldJob {
  jobId: string
  title: string
  role: 'CUSTOMER' | 'MENTOR' | 'CREW'
  crewKind?: 'ASSIGNED' | 'ACTIVE' | 'FORMER'
  state?: string
}

/** "Công việc" (`GET /api/v1/my-jobs`) listing these Jobs, each on "Sổ đặt bàn Quán Mây" and Active by default. */
export function heldJobs(...jobs: HeldJob[]): Record<string, Handler> {
  return {
    'GET /api/v1/my-jobs': () =>
      json(
        jobs.map((job) => ({
          productName: 'Sổ đặt bàn Quán Mây',
          state: 'ACTIVE',
          updatedAt: '2026-10-20T03:00:00Z',
          ...job,
        })),
      ),
  }
}

/**
 * ACC-2's GitHub row (Story 19.2): by default a session with a GitHub username is linked, with its token
 * kept and nothing keeping the link, and any other is not linked.
 */
export function gitHubLink(session: Session, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const linked = session.githubLogin !== undefined
  return {
    linked,
    ...(linked ? { login: session.githubLogin, linkedAt: '2026-10-20T03:00:00Z' } : {}),
    relinkNeeded: false,
    repositoryTokenKept: linked,
    blockers: [],
    noticeVersion: '1.0',
    ...overrides,
  }
}

/** ACC-1's read (Story 19.3): by default an empty profile with nothing verified. */
export function profileView(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    kind: 'INDIVIDUAL',
    skills: [],
    links: [],
    repositories: [],
    hideStudentDetails: false,
    verified: {},
    ...overrides,
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

/**
 * Replaces `fetch` with a backend that answers `GET /api/v1/session` with `session` and any other
 * `METHOD /path` from `handlers`, where a `*` in a handler's path stands for one path segment; the to-dos,
 * notifications, Jobs held (`/my-jobs`), bids and Crew Applications are empty, GitHub is as {@link gitHubLink} says, the profile is
 * {@link profileView}'s, and the verification is `UNVERIFIED` unless a handler answers them, and an unknown
 * request answers 404. Returns the requests it saw.
 */
export function fakeBackend(session: Session, handlers: Record<string, Handler> = {}): Request[] {
  const seen: Request[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      seen.push(request)
      const key = `${request.method} ${new URL(request.url, 'http://localhost').pathname}`
      const handler = handlers[key] ?? matching(handlers, key)
      if (handler) {
        return handler(request)
      }
      if (key === 'GET /api/v1/session') {
        return json(session)
      }
      if (key === 'GET /api/v1/environment') {
        return json({ name: 'dev' })
      }
      // A signed-in account with nothing to do and nothing new, unless the test says otherwise.
      if (key === 'GET /api/v1/todos') {
        return json({ items: [], total: 0 })
      }
      if (key === 'GET /api/v1/notifications/unread-count') {
        return json({ count: 0 })
      }
      if (
        key === 'GET /api/v1/my-jobs' ||
        key === 'GET /api/v1/mentor/applications' ||
        key === 'GET /api/v1/mentor/crew-applications'
      ) {
        return json([])
      }
      if (key === 'GET /api/v1/notifications') {
        return json({ items: [], page: 0, size: 20, total: 0 })
      }
      if (key === 'GET /api/v1/identity/github') {
        return json(gitHubLink(session))
      }
      if (key === 'GET /api/v1/profile') {
        return json(profileView())
      }
      // A Student who has not verified yet.
      if (key === 'GET /api/v1/verification') {
        return json({ status: 'UNVERIFIED' })
      }
      return json({ status: 404, code: 'NOT_FOUND' }, 404)
    }),
  )
  return seen
}

/** The handler whose key has `*` segments matching `key`, if any. */
function matching(handlers: Record<string, Handler>, key: string): Handler | undefined {
  const found = Object.keys(handlers).find((pattern) => {
    if (!pattern.includes('*')) {
      return false
    }
    const parts = pattern.split('*').map((part) => part.replace(/[.+?^${}()|[\]\\]/g, '\\$&'))
    return new RegExp(`^${parts.join('[^/]+')}$`).test(key)
  })
  return found === undefined ? undefined : handlers[found]
}
