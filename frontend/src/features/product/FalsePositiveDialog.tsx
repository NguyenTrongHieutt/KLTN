import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { inputClass } from '@/components/formField-props'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useMarkFalsePositive } from './productApi'

/**
 * CUS-5 "Không phải khóa thật" / "Không phải dữ liệu thật" (FR-8, FR-65): the dialog of the frame,
 * whose written reason is required and kept with the resolver and the time for Administrators to
 * review (FR-64).
 */
export function FalsePositiveDialog({
  productId,
  findingId,
  personal,
}: {
  productId: string
  findingId: string
  personal: boolean
}) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const mark = useMarkFalsePositive(productId, findingId)
  const toasts = useToasts()
  const field = useId()
  const kind = personal ? 'Personal' : 'Credential'
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (reason.trim() === '') {
      setMissing(true)
      return
    }
    mark.mutate(reason.trim(), {
      onSuccess: () => {
        setOpen(false)
        toasts.success(t('screen.product.finding.fpToast'))
      },
      onError: (error) => {
        setOpen(false)
        toasts.error(problemMessage(error, { module: 'scanning' }).text)
      },
    })
  }
  return (
    <>
      <Button type="button" variant="secondary" onClick={() => setOpen(true)}>
        {t(personal ? 'screen.product.finding.falsePersonal' : 'screen.product.finding.falseCredential')}
      </Button>
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t(`screen.product.finding.fpTitle${kind}`)}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.product.finding.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <p className="text-body-sm">{t(`screen.product.finding.fpCaution${kind}`)}</p>
            <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
              <div className="flex flex-col gap-1">
                <label htmlFor={field} className="text-label font-semibold">
                  {t('screen.product.finding.fpReason')}
                </label>
                <textarea
                  id={field}
                  value={reason}
                  maxLength={500}
                  rows={3}
                  onChange={(event) => {
                    setReason(event.target.value)
                    setMissing(false)
                  }}
                  aria-invalid={missing ? true : undefined}
                  aria-describedby={`${field}-helper${missing ? ` ${field}-error` : ''}`}
                  className={cn(inputClass, 'min-h-24')}
                />
                <p id={`${field}-helper`} className="text-caption text-muted-foreground">
                  {t('screen.product.finding.fpReasonHelper')}
                </p>
                {missing && (
                  <p id={`${field}-error`} role="alert" className="text-body-sm text-destructive">
                    {t('screen.product.finding.fpReasonRequired')}
                  </p>
                )}
              </div>
              <div className="flex flex-col gap-1.5">
                <p className="text-label font-semibold">{t('screen.product.finding.confirmWhat')}</p>
                <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
                  <li>{t(`screen.product.finding.fpWhat${kind}`)}</li>
                  <li>{t('screen.product.finding.fpJobs')}</li>
                  <li>{t('screen.product.finding.fpReopen')}</li>
                </ul>
              </div>
              <div className="flex flex-wrap justify-between gap-3">
                <Dialog.Close render={<Button variant="secondary" type="button" />}>
                  {t('screen.product.finding.confirmBack')}
                </Dialog.Close>
                <Button type="submit" disabled={mark.isPending}>
                  {t(`screen.product.finding.fpConfirm${kind}`)}
                </Button>
              </div>
            </form>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  )
}
