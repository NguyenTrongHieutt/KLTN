import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Session = components['schemas']['SessionView']

export const SESSION_QUERY_KEY = ['identity', 'session'] as const

/** The session as the backend sees it: anonymous, on the welcome (PUB-3), refused (locked), or signed in. */
export function fetchSession(): Promise<Session> {
  return unwrap(apiClient.GET('/api/v1/session'))
}

/** PUB-3 "Đồng ý và bắt đầu": records the accepted N-10 version and signs the account in. */
export function signUp(privacyNoticeVersion: string): Promise<Session> {
  return unwrap(apiClient.POST('/api/v1/session/sign-up', { body: { privacyNoticeVersion } }))
}

/** Ends the server session. */
export function signOut(): Promise<void> {
  return unwrap(apiClient.POST('/api/v1/session/sign-out'))
}

/** Starts the Google sign-in (FR-1), returning to `returnTo` (a path inside the SPA) afterwards. */
export function signInUrl(returnTo?: string): string {
  return withReturn('/oauth2/authorization/google', returnTo)
}

function withReturn(start: string, returnTo?: string): string {
  return returnTo ? `${start}?quay-lai=${encodeURIComponent(returnTo)}` : start
}

/**
 * Where a signed-in account lands: an Administrator in the Admin shell, everyone else, a Google account
 * with no role too, on the to-do list. No Mentor waits for an approval (Story 19.7).
 */
export function landingPath(session: Session): string {
  return session.account?.role === 'ADMINISTRATOR' ? '/quan-tri' : '/viec-can-lam'
}
