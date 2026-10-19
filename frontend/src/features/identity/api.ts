import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Session = components['schemas']['SessionView']

export type SignUpRole = components['schemas']['SignUpRequest']['role']

export const SESSION_QUERY_KEY = ['identity', 'session'] as const

/** The session as the backend sees it: anonymous, choosing a role, refused (locked), or signed in. */
export function fetchSession(): Promise<Session> {
  return unwrap(apiClient.GET('/api/v1/session'))
}

/** PUB-3: creates the account with its permanent role and the accepted notice version. */
export function signUp(role: SignUpRole, privacyNoticeVersion: string): Promise<Session> {
  return unwrap(apiClient.POST('/api/v1/session/sign-up', { body: { role, privacyNoticeVersion } }))
}

/** Ends the server session. */
export function signOut(): Promise<void> {
  return unwrap(apiClient.POST('/api/v1/session/sign-out'))
}

/** Starts the GitHub sign-in, returning to `returnTo` (a path inside the SPA) afterwards. */
export function signInUrl(returnTo?: string): string {
  return returnTo ? `/oauth2/authorization/github?quay-lai=${encodeURIComponent(returnTo)}` : '/oauth2/authorization/github'
}

/**
 * Where a signed-in account lands: an Administrator in the Admin shell, a Mentor waiting for
 * approval on MEN-5 only (FR-74), everyone else on the to-do list.
 */
export function landingPath(session: Session): string {
  const account = session.account
  if (account?.role === 'ADMINISTRATOR') {
    return '/quan-tri'
  }
  if (account?.role === 'MENTOR' && account.mentorApprovalStatus !== 'APPROVED') {
    return '/ho-so'
  }
  return '/viec-can-lam'
}
