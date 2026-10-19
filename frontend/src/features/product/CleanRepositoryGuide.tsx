import { LoaderCircle, TriangleAlert } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { inputClass } from '@/components/formField-props'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { scanActive, useRequestCleanRepository, type FindingDetail } from './productApi'

type CleanRepository = NonNullable<FindingDetail['cleanRepository']>

/**
 * CUS-5's "Đăng ký kho mã sạch" (FR-65): the link of a new public repository holding the current
 * code without personal data or history. vibefix checks it as CUS-3 does, then scans it in full;
 * the page shows the scan's progress, a refusal, or, once it passes, the resolved Finding.
 */
export function CleanRepositoryGuide({ productId, clean }: { productId: string; clean: CleanRepository | undefined }) {
  const waiting = clean?.status === 'PENDING'
  const [open, setOpen] = useState(false)
  return (
    <div className="flex flex-col gap-3">
      {clean && <CleanStatus clean={clean} />}
      {!waiting && !open && (
        <div className="flex flex-wrap gap-3">
          <Button type="button" onClick={() => setOpen(true)}>
            {t('screen.product.finding.clean.open')}
          </Button>
        </div>
      )}
      {!waiting && open && <CleanForm productId={productId} onClose={() => setOpen(false)} />}
    </div>
  )
}

function CleanStatus({ clean }: { clean: CleanRepository }) {
  const name = clean.repositoryFullName
  if (clean.status === 'PENDING') {
    const running = scanActive(clean.scan) && clean.scan?.status !== 'RETRYING'
    return running ? (
      <div role="status" aria-live="polite" className="flex items-center gap-3 rounded-md bg-info-soft p-4">
        <LoaderCircle aria-hidden="true" className="size-5 shrink-0 animate-spin text-primary motion-reduce:animate-none" />
        <p className="text-body-sm break-words">{t('screen.product.finding.clean.scanning', { name })}</p>
      </div>
    ) : (
      <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
        <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
        <p className="text-body-sm break-words">{t('screen.product.finding.clean.notFinished', { name })}</p>
      </div>
    )
  }
  if (clean.status === 'REJECTED') {
    const linked = clean.reason === 'REPO_ALREADY_LINKED'
    return (
      <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
        <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
        <div className="flex flex-col gap-0.5">
          <p className="text-label font-semibold break-words">
            {t(linked ? 'screen.product.finding.clean.linkedTitle' : 'screen.product.finding.clean.rejectedTitle', { name })}
          </p>
          {!linked && <p className="text-body-sm">{t('screen.product.finding.clean.rejectedBody')}</p>}
        </div>
      </div>
    )
  }
  return null
}

function CleanForm({ productId, onClose }: { productId: string; onClose: () => void }) {
  const [url, setUrl] = useState('')
  const request = useRequestCleanRepository(productId)
  const field = useId()
  const error = request.isError ? problemMessage(request.error, { module: 'product' }).text : undefined
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (url.trim() !== '') {
      request.mutate(url.trim(), { onSuccess: onClose })
    }
  }
  return (
    <form onSubmit={submit} className="flex flex-col gap-3 rounded-md border border-border p-4" noValidate>
      <p className="text-label font-semibold">{t('screen.product.finding.clean.heading')}</p>
      <p className="text-body-sm text-muted-foreground">{t('screen.product.finding.clean.lead')}</p>
      <div className="flex flex-col gap-1">
        <label htmlFor={field} className="text-label font-semibold">
          {t('screen.product.finding.clean.urlLabel')}
        </label>
        <input
          id={field}
          type="url"
          inputMode="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={`${field}-helper${error ? ` ${field}-error` : ''}`}
          className={inputClass}
        />
        <p id={`${field}-helper`} className="text-caption text-muted-foreground">
          {t('screen.product.finding.clean.urlHelper')}
        </p>
        {error && (
          <p id={`${field}-error`} role="alert" className="text-body-sm text-destructive">
            {error}
          </p>
        )}
      </div>
      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={request.isPending || url.trim() === ''}>
          {t(request.isPending ? 'screen.product.finding.clean.submitting' : 'screen.product.finding.clean.submit')}
        </Button>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t('screen.product.finding.clean.cancel')}
        </Button>
      </div>
    </form>
  )
}
