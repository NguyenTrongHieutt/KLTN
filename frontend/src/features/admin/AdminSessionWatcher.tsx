import { Dialog } from '@base-ui/react/dialog'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { SESSION_QUERY_KEY } from '@/features/identity/api'
import { useSession, useSignOut } from '@/features/identity/useSession'
import { t } from '@/i18n/t'
import { useSessionActivity } from './adminApi'

/** How long before the session ends the warning appears (UX-DR26, frame ADM-1-sap-het-phien). */
const WARNING_MS = 2 * 60_000

/** Real input pings the server at most this often; polling never does (SG-10). */
const PING_EVERY_MS = 60_000

/**
 * Keeps an Administrator session honest about its limits (SG-10): real input (a key or a pointer)
 * tells the server at most once a minute; 2 minutes before the earlier limit it warns, with
 * "Tiếp tục phiên" while the idle limit is the nearer one; at the limit it reloads the session, which
 * then reads anonymous and sends the screen to PUB-6.
 */
export function AdminSessionWatcher() {
  const session = useSession()
  const queryClient = useQueryClient()
  const activity = useSessionActivity()
  const signOut = useSignOut()
  const navigate = useNavigate()
  const lastPing = useRef(0)
  const administrator = session.data?.administrator
  const ping = activity.mutate
  const idle = administrator ? Date.parse(administrator.idleExpiresAt) : 0
  const absolute = administrator ? Date.parse(administrator.absoluteExpiresAt) : 0
  const ends = Math.min(idle, absolute)
  // The end the warning fired for; new limits (after "Tiếp tục phiên") make it stale, which closes it.
  const [warnedFor, setWarnedFor] = useState<number | null>(null)
  const warning = administrator && warnedFor === ends ? (absolute <= idle ? 'absolute' : 'idle') : null

  useEffect(() => {
    function onInput() {
      const now = Date.now()
      if (now - lastPing.current >= PING_EVERY_MS) {
        lastPing.current = now
        ping()
      }
    }
    window.addEventListener('keydown', onInput)
    window.addEventListener('pointerdown', onInput)
    return () => {
      window.removeEventListener('keydown', onInput)
      window.removeEventListener('pointerdown', onInput)
    }
  }, [ping])

  useEffect(() => {
    if (!ends) {
      return undefined
    }
    const warn = window.setTimeout(() => setWarnedFor(ends), Math.max(0, ends - WARNING_MS - Date.now()))
    const end = window.setTimeout(
      () => queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY }),
      Math.max(0, ends - Date.now()),
    )
    return () => {
      window.clearTimeout(warn)
      window.clearTimeout(end)
    }
  }, [ends, queryClient])

  function leave() {
    signOut.mutate(undefined, { onSettled: () => navigate('/quan-tri/dang-nhap', { replace: true }) })
  }

  return (
    <Dialog.Root open={warning !== null} onOpenChange={(open) => !open && setWarnedFor(null)}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(460px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('screen.admin.idle.title')}</Dialog.Title>
          <Dialog.Description className="text-body-sm">
            {t(warning === 'absolute' ? 'screen.admin.idle.absoluteBody' : 'screen.admin.idle.body')}
          </Dialog.Description>
          <div className="flex flex-wrap justify-between gap-3">
            <Button variant="secondary" onClick={leave}>
              {t('screen.admin.idle.signOut')}
            </Button>
            {warning === 'idle' ? (
              <Button
                disabled={activity.isPending}
                onClick={() => {
                  lastPing.current = Date.now()
                  activity.mutate()
                }}
              >
                {t('screen.admin.idle.continue')}
              </Button>
            ) : (
              <Dialog.Close render={<Button />}>{t('screen.admin.idle.close')}</Dialog.Close>
            )}
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
