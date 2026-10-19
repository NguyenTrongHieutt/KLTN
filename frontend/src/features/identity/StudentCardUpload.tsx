import { Clock, TriangleAlert, Upload } from 'lucide-react'
import { useRef, useState, type DragEvent } from 'react'
import { usePollHealth } from '@/app/usePollHealth'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { precheckUpload, STUDENT_CARD, uploadMessage } from '@/features/files/uploadRules'
import { t } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { useUploadStudentCard, type Verification } from './verificationApi'

/**
 * STU-5's student-card tab (FR-5, UX-DR45, UX-DR27): the drop area with "Chọn ảnh thẻ" and who sees
 * the card; once uploaded, "Thẻ sinh viên đang chờ quản trị viên duyệt."; after a rejection, the
 * reason, how to retake it, "Tải lên thẻ khác", and the school email as the other way. The type and
 * the size are checked here first; the server checks the bytes again.
 */
export function StudentCardUpload({ verification, onUseEmail }: { verification: Verification; onUseEmail: () => void }) {
  const card = verification.studentCard
  const upload = useUploadStudentCard()
  const toasts = useToasts()
  const { offline } = usePollHealth()
  const input = useRef<HTMLInputElement>(null)
  const [error, setError] = useState<string>()

  function choose(file: File | undefined) {
    if (!file) {
      return
    }
    const refusal = precheckUpload(file, STUDENT_CARD)
    if (refusal) {
      setError(refusal)
      return
    }
    setError(undefined)
    upload.mutate(file, {
      onSuccess: () => toasts.success(t('screen.student.verification.cardUploaded')),
      onError: (failure) => setError(uploadMessage(failure)),
    })
  }

  function drop(event: DragEvent) {
    event.preventDefault()
    choose(event.dataTransfer.files[0])
  }

  if (card?.status === 'PENDING') {
    return (
      <div role="status" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4">
        <Clock aria-hidden="true" className="size-5 shrink-0 text-primary" />
        <div className="flex flex-col gap-0.5">
          <p className="text-label font-semibold">{t('screen.student.verification.cardPending')}</p>
          <p className="text-body-sm text-muted-foreground">
            {t('screen.student.verification.cardPendingFile', {
              name: card.fileName,
              time: formatDayMonthTime(card.uploadedAt),
            })}
          </p>
        </div>
      </div>
    )
  }

  const rejected = card?.status === 'REJECTED'
  return (
    <div className="flex flex-col gap-3">
      {rejected && (
        <div role="alert" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
          <div className="flex flex-col gap-0.5">
            <p className="text-label font-semibold">
              {t('screen.student.verification.cardRejectedTitle', { reason: card.rejectionReason ?? '' })}
            </p>
            <p className="text-body-sm">{t('screen.student.verification.cardRejectedBody')}</p>
          </div>
        </div>
      )}
      {!rejected && <p className="text-label">{t('screen.student.verification.cardHeading')}</p>}
      <div
        onDragOver={(event) => event.preventDefault()}
        onDrop={drop}
        className="flex flex-col items-center gap-2 rounded-md border-2 border-dashed border-border bg-card p-6 text-center"
      >
        <Upload aria-hidden="true" className="size-6 text-muted-foreground" />
        <input
          ref={input}
          type="file"
          hidden
          accept={STUDENT_CARD.types.join(',')}
          onChange={(event) => {
            choose(event.target.files?.[0])
            event.target.value = ''
          }}
        />
        <Button
          type="button"
          variant={rejected ? 'secondary' : 'ghost'}
          size="compact"
          aria-describedby={error ? 'studentCardCaption studentCardError' : 'studentCardCaption'}
          disabled={upload.isPending || offline}
          onClick={() => input.current?.click()}
        >
          {upload.isPending
            ? t('screen.student.verification.cardUploading')
            : rejected
              ? t('screen.student.verification.cardChooseAnother')
              : t('screen.student.verification.cardChoose')}
        </Button>
        <p id="studentCardCaption" className="text-caption text-muted-foreground">
          {t('screen.student.verification.cardCaption')}
        </p>
        {upload.isPending && (
          <div
            role="progressbar"
            aria-label={t('screen.student.verification.cardProgress')}
            className="h-1 w-full max-w-xs overflow-hidden rounded-full bg-muted"
          >
            <div className="h-full w-full animate-pulse bg-primary" />
          </div>
        )}
      </div>
      {error && (
        <p id="studentCardError" role="alert" className="text-caption text-destructive">
          {error}
        </p>
      )}
      {rejected && (
        <p className="text-body-sm text-muted-foreground">
          {t('screen.student.verification.cardUseEmail')}{' '}
          <button type="button" onClick={onUseEmail} className="text-primary underline">
            {t('screen.student.verification.cardUseEmailAction')}
          </button>
        </p>
      )}
    </div>
  )
}
