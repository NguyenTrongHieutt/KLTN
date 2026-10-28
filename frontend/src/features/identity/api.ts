import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Session = components['schemas']['SessionView']

/**
 * What the signed-in account can show for itself (FR-80, Story 20.1): GitHub, LinkedIn and student
 * verification, and the lock, read by the backend on every request. The SPA's gates read it (UX-DR95).
 */
export type Capabilities = components['schemas']['CapabilitiesView']

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

/** Whether the session is an Administrator's, by the account's kind (Story 20.1), never by a role. */
export function isAdministrator(session: Session | undefined): boolean {
  return session?.account?.kind === 'ADMINISTRATOR'
}

/**
 * Where a signed-in account lands: an Administrator in the Admin shell, every user on the to-do list of every role
 * they hold (CUS-1, UX-DR88). No Mentor waits for an approval (Story 19.7).
 */
export function landingPath(session: Session): string {
  return isAdministrator(session) ? '/quan-tri' : '/viec-can-lam'
}
