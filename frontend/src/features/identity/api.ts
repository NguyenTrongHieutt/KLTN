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

/**
 * The GitHub start of "Đăng nhập lại bằng GitHub" (`/dang-nhap-lai`). It signs nobody in since Story
 * 19.1; Story 19.2 turns the page into ACC-2's re-link.
 */
export function gitHubSignInUrl(returnTo?: string): string {
  return withReturn('/oauth2/authorization/github', returnTo)
}

function withReturn(start: string, returnTo?: string): string {
  return returnTo ? `${start}?quay-lai=${encodeURIComponent(returnTo)}` : start
}

/**
 * Where a signed-in account lands: an Administrator in the Admin shell, a Mentor from before Google
 * sign-in waiting for approval on MEN-5 only (FR-74), everyone else, a Google account with no role
 * too, on the to-do list.
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
