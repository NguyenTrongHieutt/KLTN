import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useRef, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { signInUrl } from './api'
import { sessionExpiry } from './sessionExpiry'

/**
 * "Phiên đăng nhập đã hết" (UX-DR26, frame CHUNG-het-phien): shown when a command answers 401. The
 * screen behind it keeps its input; "Đăng nhập lại" goes through GitHub and back to this route.
 */
export function SessionExpiredDialog() {
  const open = useSyncExternalStore(sessionExpiry.subscribe, sessionExpiry.isExpired, () => false)
  const location = useLocation()
  const titleRef = useRef<HTMLHeadingElement>(null)

  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && sessionExpiry.dismiss()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup
          initialFocus={titleRef}
          className="fixed top-24 left-1/2 z-50 flex w-[min(460px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md"
        >
          <div className="flex items-start justify-between gap-4">
            <Dialog.Title ref={titleRef} tabIndex={-1} className="text-h2 focus:outline-none">
              {t('screen.shared.sessionExpired.title')}
            </Dialog.Title>
            <Dialog.Close
              aria-label={t('screen.shared.sessionExpired.close')}
              className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'px-2')}
            >
              <X aria-hidden="true" />
            </Dialog.Close>
          </div>
          <Dialog.Description className="text-body-sm">{t('screen.shared.sessionExpired.body')}</Dialog.Description>
          <div className="flex justify-end">
            <a href={signInUrl(location.pathname + location.search)} className={buttonVariants()}>
              {t('screen.shared.sessionExpired.signIn')}
            </a>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
