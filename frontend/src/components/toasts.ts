import { Toast } from '@base-ui/react/toast'

/** How long a success toast stays (UX-DR43). */
export const SUCCESS_TIMEOUT_MS = 5_000

/** A toast's way forward, a route inside the SPA: ACC-2 for a refusal that needs GitHub linked. */
export interface ToastLink {
  href: string
  label: string
}

/** What a toast carries besides its title. */
export interface ToastData {
  link?: ToastLink
}

/**
 * Shows outcomes: success hides after 5 s at low priority; an error stays, at high priority, with its way
 * forward when it has one.
 */
export function useToasts() {
  const manager = Toast.useToastManager<ToastData>()
  return {
    success: (title: string) => manager.add({ title, type: 'success', priority: 'low', timeout: SUCCESS_TIMEOUT_MS }),
    error: (title: string, link?: ToastLink) =>
      manager.add({ title, type: 'error', priority: 'high', timeout: 0, data: link ? { link } : undefined }),
  }
}
