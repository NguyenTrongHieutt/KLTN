import { ImagePlus, Plus, X } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
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
import {
  MAX_LINKS,
  MAX_LINK_LABEL,
  MAX_LINK_URL,
  MAX_SCREENSHOTS,
  domainOf,
  isHttps,
  newLink,
  type Evidence,
  type EvidenceLink,
  type EvidenceUpdate,
  type LinkKind,
} from './evidenceRules'

/**
 * "Tạo bàn giao"'s evidence (FR-10, FR-32, UX-DR27, UX-DR54, Story 24.3). A Code Job's preview part: the `https`
 * preview link with "Kiểm tra đường dẫn", or "Không thể làm bản xem thử" with a reason, whose recording then goes
 * in as a "Bản ghi / video" link (never an upload). An Assessment Job changes no running Product, so it has no
 * preview part; its report goes in as a "Tài liệu" link. Then the Handover evidence list: up to ten links, each
 * with its kind, a short label and the domain read from its URL -- vibefix never opens them -- and up to five
 * images from the Mentor's uploads. Every change is an update of the evidence as it is when it lands, so an upload
 * that answers after the Mentor typed elsewhere keeps what they typed.
 */
export function EvidenceFields({
  jobId,
  required,
  assessment,
  uploads,
  value,
  onChange,
}: {
  jobId: string
  required: boolean
  assessment: boolean
  uploads: HandoverScreenshot[]
  value: Evidence
  onChange: (update: EvidenceUpdate) => void
}) {
  const ids = useId()
  const add = useRef<HTMLButtonElement>(null)
  // Where focus goes once the rows change: the new row's first field, or "Thêm liên kết" after a removal.
  const [focusRow, setFocusRow] = useState<string>()
  const focusAdd = useRef(false)
  useEffect(() => {
    if (focusAdd.current) {
      focusAdd.current = false
      add.current?.focus()
    }
  }, [value.links.length])
  const setLink = (id: string, next: Partial<EvidenceLink>) =>
    onChange((prev) => ({ ...prev, links: prev.links.map((link) => (link.id === id ? { ...link, ...next } : link)) }))
  const removeLink = (id: string) => {
    focusAdd.current = true
    onChange((prev) => ({ ...prev, links: prev.links.filter((link) => link.id !== id) }))
  }
  const addLink = () => {
    const link = newLink(assessment ? 'DOCUMENT' : 'VIDEO')
    setFocusRow(link.id)
    onChange((prev) => ({ ...prev, links: [...prev.links, link] }))
  }
  const full = value.links.length >= MAX_LINKS

  return (
    <>
      {!assessment && <PreviewFields jobId={jobId} required={required} value={value} onChange={onChange} />}
      <section aria-labelledby={`${ids}-evidence`} className="flex flex-col gap-4 rounded-lg border border-border bg-card p-4">
        <h2 id={`${ids}-evidence`} className="text-h2">
          {t('screen.handover.links.title')}
        </h2>
        <div className="flex flex-col gap-2">
          <h3 className="text-h3">
            <TermHint term="handoverLink" />
          </h3>
          <p className="text-caption text-muted-foreground">
            {t(assessment ? 'screen.handover.links.assessmentHelper' : 'screen.handover.links.helper')}
          </p>
          {value.links.length === 0 && <p className="text-body-sm text-muted-foreground">{t('screen.handover.links.none')}</p>}
          {value.links.length > 0 && (
            <ol className="flex flex-col gap-2">
              {value.links.map((link, index) => (
                <LinkRow
                  key={link.id}
                  n={index + 1}
                  link={link}
                  focus={link.id === focusRow}
                  onChange={(next) => setLink(link.id, next)}
                  onRemove={() => removeLink(link.id)}
                />
              ))}
            </ol>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Button ref={add} type="button" variant="secondary" className="w-fit" disabled={full} onClick={addLink}>
              <Plus aria-hidden="true" className="size-4" />
              {t('screen.handover.links.add')}
            </Button>
            {full && <p className="text-caption text-muted-foreground">{t('screen.handover.links.full')}</p>}
          </div>
        </div>
        <ScreenshotFields jobId={jobId} uploads={uploads} value={value} onChange={onChange} />
      </section>
    </>
  )
}

/**
 * One link row: its kind, its label, its URL with the domain once the URL is one the server takes, or why it is not,
 * and "Xóa".
 * @param focus whether the row was just added, so its first field takes focus
 */
function LinkRow({
  n,
  link,
  focus,
  onChange,
  onRemove,
}: {
  n: number
  link: EvidenceLink
  focus: boolean
  onChange: (next: Partial<EvidenceLink>) => void
  onRemove: () => void
}) {
  const ids = useId()
  const kind = useRef<HTMLSelectElement>(null)
  useEffect(() => {
    if (focus) {
      kind.current?.focus()
    }
  }, [focus])
  const domain = domainOf(link.url)
  const domainLine = domain ? t('screen.handover.links.domain', { domain }) : undefined
  const urlError = link.url.trim() !== '' && !domain ? t('screen.handover.links.urlInvalid') : undefined
  return (
    <li>
      <fieldset className="grid gap-2 rounded-md border border-border bg-background p-3 md:grid-cols-[minmax(0,10rem)_minmax(0,1fr)_minmax(0,1.4fr)_auto] md:items-start">
        <legend className="text-label">{t('screen.handover.links.row', { n })}</legend>
        <FormField id={`${ids}-kind`} label={t('screen.handover.links.kind')}>
          <select
            ref={kind}
            {...fieldProps(`${ids}-kind`)}
            value={link.kind}
            onChange={(event) => onChange({ kind: event.target.value as LinkKind })}
            className={inputClass}
          >
            <option value="VIDEO">{t('screen.handover.links.kindVIDEO')}</option>
            <option value="DOCUMENT">{t('screen.handover.links.kindDOCUMENT')}</option>
          </select>
        </FormField>
        <FormField id={`${ids}-label`} label={t('screen.handover.links.label')}>
          <input
            {...fieldProps(`${ids}-label`)}
            maxLength={MAX_LINK_LABEL}
            value={link.label}
            onChange={(event) => onChange({ label: event.target.value })}
            className={inputClass}
          />
        </FormField>
        <FormField id={`${ids}-url`} label={t('screen.handover.links.url')} helper={domainLine} error={urlError}>
          <input
            {...fieldProps(`${ids}-url`, urlError, domainLine)}
            type="url"
            inputMode="url"
            maxLength={MAX_LINK_URL}
            value={link.url}
            onChange={(event) => onChange({ url: event.target.value })}
            className={`${inputClass} min-w-0`}
          />
        </FormField>
        <Button
          type="button"
          variant="ghost"
          size="compact"
          className="w-fit md:mt-7"
          aria-label={t('screen.handover.links.removeRow', { n })}
          onClick={onRemove}
        >
          {t('screen.handover.links.remove')}
        </Button>
      </fieldset>
    </li>
  )
}

/** A Code Job's preview link with its check, or "Không thể làm bản xem thử" with its reason (FR-32, UX-DR27). */
function PreviewFields({
  jobId,
  required,
  value,
  onChange,
}: {
  jobId: string
  required: boolean
  value: Evidence
  onChange: (update: EvidenceUpdate) => void
}) {
  const ids = useId()
  const toasts = useToasts()
  const check = useCheckPreview(jobId)
  const [checked, setChecked] = useState<PreviewCheck & { url: string }>()
  const set = (next: Partial<Evidence>) => onChange((prev) => ({ ...prev, ...next }))
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
        <FormField
          id={`${ids}-reason`}
          label={t('screen.handover.preview.reason')}
          helper={t('screen.handover.preview.noPreviewHelper')}
        >
          <textarea
            {...fieldProps(`${ids}-reason`, undefined, t('screen.handover.preview.noPreviewHelper'))}
            rows={2}
            maxLength={500}
            value={value.reason}
            onChange={(event) => set({ reason: event.target.value })}
            className={inputClass}
          />
        </FormField>
      )}
    </section>
  )
}

/** Up to five images from the Mentor's uploads, each removable while the handover is a draft (Story 8.3). */
function ScreenshotFields({
  jobId,
  uploads,
  value,
  onChange,
}: {
  jobId: string
  uploads: HandoverScreenshot[]
  value: Evidence
  onChange: (update: EvidenceUpdate) => void
}) {
  const toasts = useToasts()
  const add = useAddHandoverScreenshot(jobId)
  const remove = useRemoveHandoverScreenshot(jobId)
  const input = useRef<HTMLInputElement>(null)

  return (
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
                  onChange={(event) => {
                    const checked = event.target.checked
                    onChange((prev) => ({
                      ...prev,
                      screenshotIds: checked
                        ? [...prev.screenshotIds, upload.fileId]
                        : prev.screenshotIds.filter((id) => id !== upload.fileId),
                    }))
                  }}
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
                      onSuccess: () =>
                        onChange((prev) => ({
                          ...prev,
                          screenshotIds: prev.screenshotIds.filter((id) => id !== upload.fileId),
                        })),
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
            onSuccess: (upload) =>
              onChange((prev) =>
                prev.screenshotIds.length < MAX_SCREENSHOTS
                  ? { ...prev, screenshotIds: [...prev.screenshotIds, upload.fileId] }
                  : prev,
              ),
            onError: (error) => toasts.error(problemMessage(error, { module: 'delivery' }).text),
          })
        }}
      />
      <Button type="button" variant="secondary" className="w-fit" disabled={add.isPending} onClick={() => input.current?.click()}>
        <ImagePlus aria-hidden="true" className="size-4" />
        {t('screen.handover.preview.addScreenshot')}
      </Button>
    </div>
  )
}
