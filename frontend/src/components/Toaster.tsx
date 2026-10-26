import { Toast } from '@base-ui/react/toast'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import type { ToastData } from './toasts'

/**
 * The toasts of the signed-in shell (UX-DR43): Base UI's Toast, at most 3 visible, bottom-center
 * above the tab bar on phones and bottom-right on desktop; F6 moves focus to them. A toast is never
 * the only place an outcome shows.
 */
export function Toaster({ children }: { children: ReactNode }) {
  return (
    <Toast.Provider limit={3}>
      {children}
      <Toast.Portal>
        <Toast.Viewport className="fixed inset-x-4 bottom-[calc(var(--spacing-tab-bar-height)+1rem)] z-50 flex flex-col gap-2 md:inset-x-auto md:right-6 md:bottom-6 md:w-96">
          <ToastList />
        </Toast.Viewport>
      </Toast.Portal>
    </Toast.Provider>
  )
}

function ToastList() {
  const { toasts, close } = Toast.useToastManager<ToastData>()
  return toasts.map((toast) => (
    <Toast.Root
      key={toast.id}
      toast={toast}
      className={cn(
        'flex items-start gap-3 rounded-lg border border-border border-l-4 bg-card p-4 text-card-foreground shadow-md',
        toast.type === 'error' ? 'border-l-destructive' : 'border-l-success',
      )}
    >
      <div className="flex grow flex-col gap-1">
        <Toast.Title className="text-body-sm" />
        {toast.data?.link && (
          <Link
            to={toast.data.link.href}
            onClick={() => close(toast.id)}
            className="w-fit text-body-sm font-semibold text-primary underline-offset-4 hover:underline"
          >
            {toast.data.link.label}
          </Link>
        )}
      </div>
      <Toast.Close aria-label={t('toast.close')} className="rounded-md p-1 text-muted-foreground hover:bg-muted">
        <X aria-hidden="true" className="size-4" />
      </Toast.Close>
    </Toast.Root>
  ))
}
