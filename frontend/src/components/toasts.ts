import { Toast } from '@base-ui/react/toast'

/** How long a success toast stays (UX-DR43). */
export const SUCCESS_TIMEOUT_MS = 5_000

/** Shows outcomes: success hides after 5 s at low priority; an error stays, at high priority. */
export function useToasts() {
  const manager = Toast.useToastManager()
  return {
    success: (title: string) => manager.add({ title, type: 'success', priority: 'low', timeout: SUCCESS_TIMEOUT_MS }),
    error: (title: string) => manager.add({ title, type: 'error', priority: 'high', timeout: 0 }),
  }
}
