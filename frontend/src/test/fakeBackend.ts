import { vi } from 'vitest'
import type { Session } from '@/features/identity/api'

type Handler = (request: Request) => Response | Promise<Response>

export const anonymous: Session = { status: 'ANONYMOUS', privacyNoticeVersion: '2.0' }

/** A signed-in session of `role`; a Mentor is approved unless `mentorApprovalStatus` says otherwise. */
export function signedIn(
  role: 'CUSTOMER' | 'MENTOR' | 'STUDENT',
  mentorApprovalStatus?: 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED',
): Session {
  return {
    status: 'SIGNED_IN',
    privacyNoticeVersion: '2.0',
    githubLogin: 'octo-cat',
    account: {
      id: '00000000-0000-7000-8000-000000000001',
      role,
      theme: 'SYSTEM',
      ...(role === 'MENTOR' ? { mentorApprovalStatus: mentorApprovalStatus ?? 'APPROVED' } : {}),
    },
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
 * `METHOD /path` from `handlers`; the to-dos and notifications are empty unless a handler answers
 * them, and an unknown request answers 404. Returns the requests it saw.
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
      return json({ status: 404, code: 'NOT_FOUND' }, 404)
    }),
  )
  return seen
}
