import { vi } from 'vitest'
import type { Session } from '@/features/identity/api'

type Handler = (request: Request) => Response | Promise<Response>

export const anonymous: Session = { status: 'ANONYMOUS', privacyNoticeVersion: '3.0' }

/** A Google account on PUB-3, waiting to accept N-10 (Story 19.1). */
export const welcome: Session = { status: 'SIGNING_UP', displayName: 'Nguyễn Thị Lan', privacyNoticeVersion: '3.0' }

/** A signed-in Google account, which has no role until Epic 20 (Story 19.1). */
export const signedInWithGoogle: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '3.0',
  displayName: 'Nguyễn Thị Lan',
  account: { id: '00000000-0000-7000-8000-000000000002', theme: 'LIGHT' },
}

/** A signed-in session of `role`; no Mentor needs an approval (Story 19.7). */
export function signedIn(role: 'CUSTOMER' | 'MENTOR' | 'STUDENT'): Session {
  return {
    status: 'SIGNED_IN',
    privacyNoticeVersion: '2.0',
    githubLogin: 'octo-cat',
    account: { id: '00000000-0000-7000-8000-000000000001', role, theme: 'LIGHT' },
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
 * `METHOD /path` from `handlers`; the to-dos and notifications are empty, GitHub is as
 * {@link gitHubLink} says, the profile is {@link profileView}'s, and the verification is `UNVERIFIED`
 * unless a handler answers them, and an unknown request answers 404. Returns the requests it saw.
 */
export function fakeBackend(session: Session, handlers: Record<string, Handler> = {}): Request[] {
  const seen: Request[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      seen.push(request)
      const key = `${request.method} ${new URL(request.url, 'http://localhost').pathname}`
      if (handlers[key]) {
        return handlers[key](request)
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
