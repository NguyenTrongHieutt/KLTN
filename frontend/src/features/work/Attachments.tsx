import { Menu } from '@base-ui/react/menu'
import { ExternalLink, FileText, Paperclip, Plus } from 'lucide-react'
import { useId, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { precheckAttachment } from '@/features/files/uploadRules'
import { t } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { SensitiveDialog } from './SensitiveDialog'
import {
  useAttachFile,
  useAttachLink,
  useRemoveAttachment,
  type Resolution,
  type SensitiveMatch,
  type WorkAttachment,
  type WorkItemDetail,
} from './workApi'
import { sensitiveMatches, workRefusal } from './workRefusal'

const itemClass =
  'flex min-h-tap-min cursor-pointer items-center gap-2 rounded-md px-3 py-1.5 text-body-sm data-highlighted:bg-muted'

/** What waits for "Che và lưu": the file or the link that FR-47 stopped. */
type Pending = { kind: 'file'; file: File } | { kind: 'link'; url: string; label: string }

/**
 * The Attachment list (FR-76, NFR-3, AD-39, UX-DR79) in the Work Item detail and the Submit and Review
 * dialogs: each file with its name and size, each link by its label or domain as an External link,
 * both with author and time, and "Gỡ" for the author until the Work Item is Done. "Thêm tệp hoặc liên
 * kết" uploads one file after the NFR-3 pre-check or adds an `https` link; a likely credential or
 * customer data opens the FR-47 dialog first. Never on a Customer screen.
 */
export function AttachmentList({
  detail,
  canAdd,
  canRemove = true,
}: {
  detail: WorkItemDetail
  /** The viewer writes the Board: the Mentor or an active member while the Job is Active. */
  canAdd: boolean
  /** Whether "Gỡ" shows where the server allows it; the Review dialog only reads. */
  canRemove?: boolean
}) {
  const ids = useId()
  const toasts = useToasts()
  const itemId = detail.item.id
  const attachFile = useAttachFile(itemId)
  const attachLink = useAttachLink(itemId)
  const remove = useRemoveAttachment(itemId)
  const fileInput = useRef<HTMLInputElement>(null)
  const [linkForm, setLinkForm] = useState(false)
  const [url, setUrl] = useState('')
  const [label, setLabel] = useState('')
  const [error, setError] = useState<string>()
  const [matches, setMatches] = useState<SensitiveMatch[]>()
  const [pending, setPending] = useState<Pending>()

  const refused = (failure: unknown, waiting: Pending) => {
    const found = sensitiveMatches(failure)
    if (found) {
      setPending(waiting)
      setMatches(found)
      return
    }
    setMatches(undefined)
    setError(workRefusal(failure))
  }

  const upload = (file: File, sensitive: Resolution) => {
    setError(undefined)
    attachFile.mutate(
      { file, sensitive },
      {
        onSuccess: () => {
          setMatches(undefined)
          toasts.success(t('screen.board.attachments.added', { name: file.name }))
        },
        onError: (failure) => refused(failure, { kind: 'file', file }),
      },
    )
  }

  const link = (target: { url: string; label: string }, sensitive: Resolution) => {
    setError(undefined)
    attachLink.mutate(
      { ...target, sensitive },
      {
        onSuccess: () => {
          setMatches(undefined)
          setLinkForm(false)
          setUrl('')
          setLabel('')
        },
        onError: (failure) => refused(failure, { kind: 'link', ...target }),
      },
    )
  }

  const pick = (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = event.target.files?.[0]
    event.target.value = ''
    if (!chosen) {
      return
    }
    const refusal = precheckAttachment(chosen)
    if (refusal) {
      setError(refusal)
      return
    }
    upload(chosen, 'CHECK')
  }

  const onLink = (event: FormEvent) => {
    event.preventDefault()
    if (!/^https:\/\/\S+$/i.test(url.trim())) {
      setError(t('reason.work.LINK_INVALID'))
      return
    }
    link({ url: url.trim(), label: label.trim() }, 'CHECK')
  }

  const mask = () => {
    if (pending?.kind === 'file') {
      upload(pending.file, 'MASK')
    } else if (pending?.kind === 'link') {
      link({ url: pending.url, label: pending.label }, 'MASK')
    }
  }

  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-2">
      <h3 id={`${ids}-title`} className="text-h3">
        {t('screen.board.attachments.title')}
      </h3>
      {detail.attachments.length === 0 ? (
        <p className="text-caption text-muted-foreground">{t('screen.board.attachments.empty')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {detail.attachments.map((attachment) => (
            <AttachmentRow
              key={attachment.id}
              attachment={attachment}
              removable={canRemove && attachment.removable}
              pending={remove.isPending}
              onRemove={() =>
                remove.mutate(attachment.id, {
                  onSuccess: () => toasts.success(t('screen.board.attachments.removed', { name: attachment.name })),
                  onError: (failure) => setError(workRefusal(failure)),
                })
              }
            />
          ))}
        </ul>
      )}
      {attachFile.isPending && (
        <p role="status" className="text-caption text-muted-foreground">
          {t('screen.board.attachments.uploading')}
        </p>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {canAdd && (
        <>
          <input ref={fileInput} type="file" className="hidden" tabIndex={-1} onChange={pick} data-testid="attachment-file" />
          {linkForm ? (
            <form onSubmit={onLink} className="flex flex-col gap-2 rounded-md border border-border p-3" noValidate>
              <label htmlFor={`${ids}-url`} className="text-label">
                {t('screen.board.attachments.url')}
              </label>
              <input
                id={`${ids}-url`}
                type="url"
                inputMode="url"
                value={url}
                maxLength={2000}
                placeholder="https://"
                onChange={(event) => setUrl(event.target.value)}
                className={inputClass}
              />
              <label htmlFor={`${ids}-label`} className="text-label">
                {t('screen.board.attachments.label')}
              </label>
              <input
                id={`${ids}-label`}
                value={label}
                maxLength={200}
                onChange={(event) => setLabel(event.target.value)}
                className={inputClass}
              />
              <div className="flex flex-wrap justify-end gap-2">
                <Button type="button" variant="secondary" size="compact" onClick={() => setLinkForm(false)}>
                  {t('screen.board.attachments.cancel')}
                </Button>
                <Button type="submit" size="compact" disabled={attachLink.isPending}>
                  {t('screen.board.attachments.saveLink')}
                </Button>
              </div>
            </form>
          ) : (
            <Menu.Root>
              <Menu.Trigger
                className={buttonVariants({ variant: 'secondary', size: 'compact', className: 'self-start' })}
                disabled={attachFile.isPending}
              >
                <Plus aria-hidden="true" />
                {t('screen.board.attachments.add')}
              </Menu.Trigger>
              <Menu.Portal>
                <Menu.Positioner align="start" sideOffset={4} className="z-[80]">
                  <Menu.Popup className="min-w-52 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-md">
                    <Menu.Item className={itemClass} onClick={() => fileInput.current?.click()}>
                      <Paperclip aria-hidden="true" className="size-4" />
                      {t('screen.board.attachments.addFile')}
                    </Menu.Item>
                    <Menu.Item
                      className={itemClass}
                      onClick={() => {
                        setError(undefined)
                        setLinkForm(true)
                      }}
                    >
                      <ExternalLink aria-hidden="true" className="size-4" />
                      {t('screen.board.attachments.addLink')}
                    </Menu.Item>
                  </Menu.Popup>
                </Menu.Positioner>
              </Menu.Portal>
            </Menu.Root>
          )}
        </>
      )}
      {matches && (
        <SensitiveDialog
          matches={matches}
          pending={attachFile.isPending || attachLink.isPending}
          onMask={mask}
          onEdit={() => {
            setMatches(undefined)
            setPending(undefined)
          }}
        />
      )}
    </section>
  )
}

function AttachmentRow({
  attachment,
  removable,
  pending,
  onRemove,
}: {
  attachment: WorkAttachment
  removable: boolean
  pending: boolean
  onRemove: () => void
}) {
  const meta = t('screen.board.attachments.meta', {
    author: attachment.author,
    time: formatDayMonthTime(attachment.addedAt),
  })
  return (
    <li className="flex items-start justify-between gap-3 rounded-md bg-muted px-3 py-2 text-body-sm">
      <span className="flex min-w-0 items-start gap-2">
        {attachment.kind === 'LINK' ? (
          <ExternalLink aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        ) : (
          <FileText aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        )}
        <span className="flex min-w-0 flex-col gap-0.5">
          {attachment.kind === 'LINK' ? (
            <a
              href={attachment.url?.startsWith('https://') ? attachment.url : undefined}
              target="_blank"
              rel="noopener noreferrer nofollow"
              className="break-words text-primary underline-offset-4 hover:underline"
            >
              {attachment.name}
              <span className="sr-only"> ({t('screen.board.attachments.external')})</span>
            </a>
          ) : (
            <a
              href={attachment.downloadUrl ?? undefined}
              download
              className="break-all text-primary underline-offset-4 hover:underline"
            >
              {attachment.name}
            </a>
          )}
          <span className="text-caption text-muted-foreground">
            {attachment.kind === 'LINK'
              ? `${attachment.domain ?? ''} · ${meta}`
              : `${formatSize(attachment.size ?? 0)} · ${meta}`}
          </span>
        </span>
      </span>
      {removable && (
        <Button
          type="button"
          variant="ghost"
          size="compact"
          disabled={pending}
          aria-label={t('screen.board.attachments.removeLabel', { name: attachment.name })}
          onClick={onRemove}
        >
          {t('screen.board.attachments.remove')}
        </Button>
      )}
    </li>
  )
}
