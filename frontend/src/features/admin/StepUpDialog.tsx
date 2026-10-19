import { Dialog } from '@base-ui/react/dialog'
import { useRef, useState, type FormEvent } from 'react'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { fieldMessage } from '@/lib/formErrors'
import { cn } from '@/lib/utils'
import { verificationMessage } from '@/features/identity/verificationMessages'

/**
 * The Step-up dialog (SG-10, UX-DR42): a fresh code from the authenticator before a sensitive
 * command. `onConfirm` runs the command with the code; a wrong code keeps the dialog open with the
 * attempts left, and any other refusal shows its sentence.
 */
export function StepUpDialog({
  open,
  onOpenChange,
  confirmLabel,
  pending,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  confirmLabel?: string
  pending: boolean
  onConfirm: (code: string, refused: (error: unknown) => void) => void
}) {
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const inputRef = useRef<HTMLInputElement>(null)

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!/^[0-9]{6}$/.test(code)) {
      setError(fieldMessage('format'))
      return
    }
    setError(undefined)
    onConfirm(code, (refusal) => {
      setCode('')
      const left = refusal instanceof ApiError ? refusal.problem.attemptsLeft : undefined
      setError(
        refusal instanceof ApiError && refusal.problem.reason === 'STEP_UP_FAILED' && left !== undefined
          ? t('screen.admin.stepUp.attemptsLeft', { n: left })
          : verificationMessage(refusal),
      )
    })
  }

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setCode('')
          setError(undefined)
        }
        onOpenChange(next)
      }}
    >
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup
          initialFocus={inputRef}
          className="fixed top-24 left-1/2 z-50 flex w-[min(440px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md"
        >
          <Dialog.Title className="text-h2">
            {t('screen.admin.stepUp.title')}
          </Dialog.Title>
          <Dialog.Description className="text-body-sm">{t('screen.admin.stepUp.body')}</Dialog.Description>
          <form noValidate onSubmit={submit} className="flex flex-col gap-4">
            <FormField id="stepUpCode" label={t('screen.admin.stepUp.code')} error={error}>
              <input
                {...fieldProps('stepUpCode', error)}
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                ref={inputRef}
                className={cn(inputClass, 'max-w-[200px] font-mono tracking-[0.3em]')}
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/[^0-9]/g, ''))}
              />
            </FormField>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.stepUp.back')}</Dialog.Close>
              <Button type="submit" disabled={pending}>
                {pending ? t('screen.admin.stepUp.confirming') : (confirmLabel ?? t('screen.admin.stepUp.confirm'))}
              </Button>
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
