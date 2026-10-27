import { Dialog } from '@base-ui/react/dialog'
import { Menu } from '@base-ui/react/menu'
import { useMutation } from '@tanstack/react-query'
import { Ellipsis } from 'lucide-react'
import { useId, useRef, useState, type FormEvent } from 'react'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { useSession } from '@/features/identity/useSession'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError, apiClient, unwrap } from '@/lib/apiClient'
import { fieldMessage } from '@/lib/formErrors'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'

export type ReportKind =
  | 'PRODUCT'
  | 'JOB_POST'
  | 'PAGE'
  | 'RATING'
  | 'JOB_CHAT_MESSAGE'
  | 'CREW_CHAT_MESSAGE'
  | 'WORK_ITEM_NOTE'
  | 'USER'

/**
 * What one "Báo cáo vi phạm" item reports; `item` names it when the menu offers more than one. A user is
 * named by `targetLogin` where their profile shows no account (a Student's, PUB-4).
 */
export interface ReportTarget {
  kind: ReportKind
  targetId?: string
  targetLogin?: string
  item?: MessageKey
}

const itemClass =
  'flex min-h-tap-min cursor-pointer items-center rounded-md px-3 py-1.5 text-body-sm data-highlighted:bg-muted'

/**
 * Report abuse (FR-69, AD-34, UX-DR64): the "…" menu of a Product, Job Post, Page, Rating, chat message,
 * Work Item note, or public profile. The dialog requires a reason, and once sent reads "Quản trị viên sẽ
 * xem xét và báo kết quả cho bạn." A refusal (not visible any more, reported already, 10 reports today)
 * shows its sentence.
 */
export function ReportAbuseMenu({ targets, label }: { targets: ReportTarget[]; label: string }) {
  const [reporting, setReporting] = useState<ReportTarget>()
  return (
    <>
      <Menu.Root>
        <Menu.Trigger aria-label={label} className={buttonVariants({ variant: 'ghost', size: 'compact' })}>
          <Ellipsis aria-hidden="true" className="size-4" />
        </Menu.Trigger>
        <Menu.Portal>
          <Menu.Positioner align="end" sideOffset={4} className="z-[60]">
            <Menu.Popup className="min-w-48 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md">
              {targets.map((target) => (
                <Menu.Item
                  key={`${target.kind}-${target.targetId ?? target.targetLogin}`}
                  className={itemClass}
                  onClick={() => setReporting(target)}
                >
                  {t(target.item ?? 'screen.report.item')}
                </Menu.Item>
              ))}
            </Menu.Popup>
          </Menu.Positioner>
        </Menu.Portal>
      </Menu.Root>
      {reporting && (
        <ReportDialog
          key={`${reporting.kind}-${reporting.targetId ?? reporting.targetLogin}`}
          target={reporting}
          onClose={() => setReporting(undefined)}
        />
      )}
    </>
  )
}

/**
 * "Báo cáo vi phạm" on a public profile (PUB-4, PUB-5): for a signed-in user other than the person shown,
 * never an Administrator.
 */
export function ReportUserMenu({
  accountId,
  login,
  name,
}: {
  accountId?: string
  /** For a Student's profile, which shows no account; the viewer's own profile is not known then. */
  login?: string
  name: string
}) {
  const session = useSession()
  const account = session.data?.status === 'SIGNED_IN' ? session.data.account : undefined
  if (!account || account.kind === 'ADMINISTRATOR' || (accountId !== undefined && account.id === accountId)) {
    return null
  }
  return (
    <ReportAbuseMenu
      targets={[{ kind: 'USER', targetId: accountId, targetLogin: accountId === undefined ? login : undefined }]}
      label={t('screen.report.profileMore', { name })}
    />
  )
}

function ReportDialog({ target, onClose }: { target: ReportTarget; onClose: () => void }) {
  const ids = useId()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const [sent, setSent] = useState(false)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const report = useMutation({
    mutationFn: (text: string) =>
      unwrap(
        apiClient.POST('/api/v1/abuse-reports', {
          body: { kind: target.kind, targetId: target.targetId, targetLogin: target.targetLogin, reason: text },
        }),
      ),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const text = reason.trim()
    if (text === '') {
      setError(fieldMessage('required'))
      return
    }
    setError(undefined)
    report.mutate(text, {
      onSuccess: () => setSent(true),
      onError: (failure) =>
        setError(
          failure instanceof ApiError && failure.problem.reason === 'ABUSE_REPORT_LIMIT'
            ? t('reason.admin.ABUSE_REPORT_LIMIT')
            : problemMessage(failure, { module: 'admin' }).text,
        ),
    })
  }

  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup
          initialFocus={inputRef}
          className="fixed top-24 left-1/2 z-50 flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md"
        >
          <Dialog.Title className="text-h2">{t('screen.report.title')}</Dialog.Title>
          {sent ? (
            <>
              <p role="status" className="text-body-sm">
                {t('screen.report.sent')}
              </p>
              <div className="flex justify-end">
                <Dialog.Close render={<Button type="button" />}>{t('screen.report.close')}</Dialog.Close>
              </div>
            </>
          ) : (
            <form noValidate onSubmit={submit} className="flex flex-col gap-4">
              <Dialog.Description className="text-body-sm">{t('screen.report.body')}</Dialog.Description>
              <FormField id={`${ids}-reason`} label={t('screen.report.reason')} error={error}>
                <textarea
                  {...fieldProps(`${ids}-reason`, error)}
                  ref={inputRef}
                  value={reason}
                  maxLength={500}
                  onChange={(event) => setReason(event.target.value)}
                  className={cn(inputClass, 'min-h-24')}
                />
              </FormField>
              <div className="flex flex-wrap justify-between gap-3">
                <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.report.cancel')}</Dialog.Close>
                <Button type="submit" disabled={report.isPending}>
                  {t('screen.report.send')}
                </Button>
              </div>
            </form>
          )}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
