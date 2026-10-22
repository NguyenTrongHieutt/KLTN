import { ImagePlus, X } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { TermHint } from '@/components/TermHint'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import {
  useAddHandoverScreenshot,
  useCheckPreview,
  useRemoveHandoverScreenshot,
  type HandoverScreenshot,
  type PreviewCheck,
} from './deliveryApi'
import { MAX_SCREENSHOTS, isHttps, type Evidence } from './evidenceRules'

/**
 * "Tạo bàn giao"'s preview part (FR-32, UX-DR27, UX-DR54): the `https` preview link with "Kiểm tra đường
 * dẫn", or "Không thể làm bản xem thử" with a reason and an external recording link (never an upload),
 * and up to five screenshots from the Mentor's uploads.
 */
export function EvidenceFields({
  jobId,
  required,
  uploads,
  value,
  onChange,
}: {
  jobId: string
  required: boolean
  uploads: HandoverScreenshot[]
  value: Evidence
  onChange: (next: Evidence) => void
}) {
  const ids = useId()
  const toasts = useToasts()
  const check = useCheckPreview(jobId)
  const add = useAddHandoverScreenshot(jobId)
  const remove = useRemoveHandoverScreenshot(jobId)
  const input = useRef<HTMLInputElement>(null)
  const [checked, setChecked] = useState<PreviewCheck & { url: string }>()
  const set = (next: Partial<Evidence>) => onChange({ ...value, ...next })
  const result = checked && checked.url === value.preview.trim() ? checked : undefined
  const unreachable = result && !result.reachable ? t('reason.delivery.PREVIEW_UNREACHABLE') : undefined

  return (
    <section aria-labelledby={`${ids}-preview`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <h2 id={`${ids}-preview`} className="text-h2">
        <TermHint term="previewLink" />
      </h2>
      {required && <p className="text-body-sm text-muted-foreground">{t('screen.handover.preview.required')}</p>}
      {!value.noPreview && (
        <FormField
          id={`${ids}-url`}
          label={t('screen.handover.preview.url')}
          helper={t('screen.handover.preview.urlHelper')}
          error={unreachable}
        >
          <div className="flex flex-wrap gap-2">
            <input
              {...fieldProps(`${ids}-url`, unreachable, t('screen.handover.preview.urlHelper'))}
              type="url"
              inputMode="url"
              maxLength={500}
              value={value.preview}
              onChange={(event) => set({ preview: event.target.value })}
              className={`${inputClass} min-w-0 flex-1`}
            />
            <Button
              type="button"
              variant="secondary"
              disabled={!isHttps(value.preview) || check.isPending}
              onClick={() => {
                const url = value.preview.trim()
                check.mutate(url, {
                  onSuccess: (answer) => setChecked({ ...answer, url }),
                  onError: (error) => toasts.error(problemMessage(error, { module: 'delivery' }).text),
                })
              }}
            >
              {t('screen.handover.preview.check')}
            </Button>
          </div>
          {result?.reachable && (
            <p role="status" className="text-caption text-success">
              {t('screen.handover.preview.reachable', { domain: result.domain })}
            </p>
          )}
        </FormField>
      )}
      <label className="flex min-h-tap-min items-center gap-2 text-body-sm">
        <input type="checkbox" checked={value.noPreview} onChange={(event) => set({ noPreview: event.target.checked })} />
        {t('screen.handover.preview.impossible')}
      </label>
      {value.noPreview && (
        <>
          <FormField id={`${ids}-reason`} label={t('screen.handover.preview.reason')}>
            <textarea
              {...fieldProps(`${ids}-reason`)}
              rows={2}
              maxLength={500}
              value={value.reason}
              onChange={(event) => set({ reason: event.target.value })}
              className={inputClass}
            />
          </FormField>
          <FormField
            id={`${ids}-recording`}
            label={t('screen.handover.preview.recording')}
            helper={t('screen.handover.preview.recordingHelper')}
          >
            <input
              {...fieldProps(`${ids}-recording`, undefined, t('screen.handover.preview.recordingHelper'))}
              type="url"
              inputMode="url"
              maxLength={500}
              value={value.recording}
              onChange={(event) => set({ recording: event.target.value })}
              className={inputClass}
            />
          </FormField>
        </>
      )}
      <div className="flex flex-col gap-2">
        <h3 className="text-h3">{t('screen.handover.preview.screenshots')}</h3>
        <p className="text-caption text-muted-foreground">{t('screen.handover.preview.screenshotsHelper')}</p>
        {uploads.length > 0 && (
          <ul className="flex flex-col gap-1.5">
            {uploads.map((upload) => {
              const included = value.screenshotIds.includes(upload.fileId)
              return (
                <li key={upload.fileId} className="flex items-center gap-2 text-body-sm">
                  <input
                    type="checkbox"
                    aria-label={t('screen.handover.preview.includeScreenshot', { name: upload.fileName })}
                    checked={included}
                    disabled={!included && value.screenshotIds.length >= MAX_SCREENSHOTS}
                    onChange={(event) =>
                      set({
                        screenshotIds: event.target.checked
                          ? [...value.screenshotIds, upload.fileId]
                          : value.screenshotIds.filter((id) => id !== upload.fileId),
                      })
                    }
                  />
                  <img src={`/api/v1/files/${upload.fileId}`} alt="" className="size-10 rounded border border-border object-cover" />
                  <span className="min-w-0 flex-1 break-all">{upload.fileName}</span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="compact"
                    aria-label={t('screen.handover.preview.removeScreenshot', { name: upload.fileName })}
                    disabled={remove.isPending}
                    onClick={() =>
                      remove.mutate(upload.fileId, {
                        onSuccess: () => set({ screenshotIds: value.screenshotIds.filter((id) => id !== upload.fileId) }),
                        onError: (error) => toasts.error(problemMessage(error, { module: 'delivery' }).text),
                      })
                    }
                  >
                    <X aria-hidden="true" className="size-4" />
                  </Button>
                </li>
              )
            })}
          </ul>
        )}
        <input
          ref={input}
          type="file"
          accept="image/*"
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (!file) {
              return
            }
            add.mutate(file, {
              onSuccess: (upload) => {
                if (value.screenshotIds.length < MAX_SCREENSHOTS) {
                  set({ screenshotIds: [...value.screenshotIds, upload.fileId] })
                }
              },
              onError: (error) => toasts.error(problemMessage(error, { module: 'delivery' }).text),
            })
          }}
        />
        <Button type="button" variant="secondary" className="w-fit" disabled={add.isPending} onClick={() => input.current?.click()}>
          <ImagePlus aria-hidden="true" className="size-4" />
          {t('screen.handover.preview.addScreenshot')}
        </Button>
      </div>
    </section>
  )
}
