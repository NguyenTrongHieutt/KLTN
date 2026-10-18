import { ApiError } from '@/lib/apiClient'

/**
 * Whether a command found the session gone. The dialog then covers the screen, so what the user
 * typed stays where it is, and "Đăng nhập lại" returns to the same route (EXPERIENCE.md, Session
 * expired).
 */
const listeners = new Set<() => void>()
let expired = false

export const sessionExpiry = {
  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  },
  isExpired(): boolean {
    return expired
  },
  /** Called for every failed command; only a 401 opens the dialog. */
  report(error: unknown) {
    if (error instanceof ApiError && error.status === 401 && !expired) {
      expired = true
      listeners.forEach((listener) => listener())
    }
  },
  dismiss() {
    expired = false
    listeners.forEach((listener) => listener())
  },
}
