import { Dialog } from '@base-ui/react/dialog'
import { Lock, Paperclip, Send, TriangleAlert, X } from 'lucide-react'
import { Fragment, useEffect, useId, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { ReportAbuseMenu } from '@/features/abuse/ReportAbuseMenu'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDate, formatTime } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useChatMessages,
  useMarkRead,
  useSendAttachment,
  useSendMessage,
  type ChatMessage,
  type ChatTarget,
  type Resolution,
  type SensitiveMatch,
} from './chatApi'

/** The upload rules an attachment is pre-checked against (AD-21, NFR-3). */
const MAX_FILE_BYTES = 10 * 1024 * 1024
const ACCEPTED = ['image/jpeg', 'image/png', 'image/gif', 'image/bmp', 'application/pdf', 'text/plain']
const MAX_LINES = 6

/** On a phone Enter adds a line; on a fine pointer it sends (UX-DR40). */
function touchScreen(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches
}

/**
 * The conversation of JOB-4 and JOB-8 (FR-46, FR-47, FR-77, UX-DR25, UX-DR40, UX-DR80): the newest 50
 * messages, "Xem tin cũ hơn", day separators, own messages on primary-soft bubbles, and the composer,
 * or a note in its place once the Chat is read-only. A message that may hold a credential or customer
 * data waits for the sender's choice in the Sensitive-content dialog. Text is shown as text, never as
 * markup.
 */
export function ChatPanel({
  target,
  heading,
  audience,
  placeholder,
  empty = t('screen.chat.empty'),
  readOnlyNote,
}: {
  target: ChatTarget
  heading: string
  /** Who can read the thread, in the header. */
  audience: string
  placeholder: string
  empty?: string
  /** Shown in place of the composer when the Chat is read-only. */
  readOnlyNote?: string
}) {
  const messages = useChatMessages(target)
  const markRead = useMarkRead(target)
  const pages = messages.data?.pages ?? []
  const items = [...pages].reverse().flatMap((page) => page.items)
  const newest = items.at(-1)?.id
  const { mutate: read } = markRead
  useEffect(() => {
    if (newest) {
      read()
    }
  }, [newest, read])
  return (
    <section
      aria-label={t('screen.chat.message')}
      className="flex w-full max-w-[860px] flex-col gap-3.5 self-center rounded-lg border border-border bg-card p-5"
    >
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border pb-3">
        <h2 className="text-h3">{heading}</h2>
        <p className="flex items-center gap-1.5 text-caption text-muted-foreground">
          <Lock aria-hidden="true" className="size-4" />
          {audience}
        </p>
      </div>
      <RegionState query={messages} skeleton={<Skeleton className="h-48 w-full" />}>
        {messages.hasNextPage && (
          <Button
            type="button"
            variant="ghost"
            size="compact"
            className="self-center"
            disabled={messages.isFetchingNextPage}
            onClick={() => void messages.fetchNextPage()}
          >
            {t('screen.chat.older')}
          </Button>
        )}
        {items.length === 0 ? (
          <p className="py-6 text-center text-body-sm text-muted-foreground">{empty}</p>
        ) : (
          <ol className="flex flex-col gap-3">
            {items.map((message, index) => {
              const day = dayLabel(message.sentAt)
              const separator = index === 0 || dayLabel(items[index - 1].sentAt) !== day
              return (
                <Fragment key={message.id}>
                  {separator && (
                    <li aria-hidden="true" className="text-center text-caption text-muted-foreground">
                      {day}
                    </li>
                  )}
                  <Bubble message={message} kind={target.kind === 'job' ? 'JOB_CHAT_MESSAGE' : 'CREW_CHAT_MESSAGE'} />
                </Fragment>
              )
            })}
          </ol>
        )}
      </RegionState>
      <hr className="border-border" />
      {readOnlyNote ? (
        <p className="rounded-md bg-muted px-3 py-2.5 text-body-sm text-muted-foreground">{readOnlyNote}</p>
      ) : (
        <Composer target={target} placeholder={placeholder} />
      )}
    </section>
  )
}

function dayLabel(instant: string): string {
  const day = formatDate(instant)
  if (day === formatDate(new Date().toISOString())) {
    return t('screen.chat.today')
  }
  if (day === formatDate(new Date(Date.now() - 86_400_000).toISOString())) {
    return t('screen.chat.yesterday')
  }
  return day
}

/** One message; another person's carries "Báo cáo vi phạm" in its "…" menu (FR-69). */
function Bubble({ message, kind }: { message: ChatMessage; kind: 'JOB_CHAT_MESSAGE' | 'CREW_CHAT_MESSAGE' }) {
  return (
    <li className={cn('flex flex-col gap-1', message.mine ? 'items-end' : 'items-start')}>
      <div className="flex items-center gap-1">
        <p className="text-caption text-muted-foreground">
          {t('screen.chat.meta', { name: message.mine ? t('screen.chat.you') : message.author, time: formatTime(message.sentAt) })}
        </p>
        {!message.mine && (
          <ReportAbuseMenu
            targets={[{ kind, targetId: message.id }]}
            label={t('screen.report.messageMore', { name: message.author })}
          />
        )}
      </div>
      <div
        className={cn(
          'flex max-w-[80%] flex-col gap-1.5 rounded-lg px-3 py-2 text-body-sm',
          message.mine ? 'bg-primary-soft' : 'border border-border bg-background',
        )}
      >
        {message.body && <p className="break-words whitespace-pre-wrap">{message.body}</p>}
        {message.attachment && (
          <a
            href={`/api/v1/files/${message.attachment.fileId}`}
            className="flex w-fit items-center gap-1.5 text-primary underline-offset-4 hover:underline"
          >
            <Paperclip aria-hidden="true" className="size-4" />
            <span className="break-all">{message.attachment.name}</span>
            <span className="text-caption text-muted-foreground">{formatSize(message.attachment.size)}</span>
          </a>
        )}
      </div>
      {message.maskedCredentials > 0 && (
        <p className="text-caption text-muted-foreground">{t('screen.chat.maskedCredentials', { n: message.maskedCredentials })}</p>
      )}
      {message.maskedPersonal > 0 && (
        <p className="text-caption text-muted-foreground">{t('screen.chat.maskedPersonal', { n: message.maskedPersonal })}</p>
      )}
    </li>
  )
}

/** The composer (UX-DR40): 1 to 6 lines, Enter per pointer, the attach pre-check, and the icon send button. */
function Composer({ target, placeholder }: { target: ChatTarget; placeholder: string }) {
  const ids = useId()
  const text = useRef<HTMLTextAreaElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [body, setBody] = useState('')
  const [file, setFile] = useState<File>()
  const [warning, setWarning] = useState<SensitiveMatch[]>()
  const send = useSendMessage(target)
  const attach = useSendAttachment(target)
  const toasts = useToasts()
  const touch = touchScreen()
  const pending = send.isPending || attach.isPending

  useEffect(() => {
    const area = text.current
    if (!area) {
      return
    }
    area.style.height = 'auto'
    const line = Number.parseFloat(getComputedStyle(area).lineHeight) || 22
    area.style.height = `${Math.min(area.scrollHeight, line * MAX_LINES + 16)}px`
  }, [body])

  const submit = (sensitive: Resolution) => {
    const done = {
      onSuccess: () => {
        setBody('')
        setFile(undefined)
        setWarning(undefined)
        text.current?.focus()
      },
      onError: (error: unknown) => {
        if (error instanceof ApiError && error.problem.reason === 'SENSITIVE_CONTENT') {
          setWarning((error.problem as { matches?: SensitiveMatch[] }).matches ?? [])
          return
        }
        setWarning(undefined)
        toasts.error(problemMessage(error, { module: 'chat' }).text)
      },
    }
    if (file) {
      attach.mutate({ file, body: body.trim(), sensitive }, done)
    } else if (body.trim() !== '') {
      send.mutate({ body: body.trim(), sensitive }, done)
    }
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !touch && !event.nativeEvent.isComposing) {
      event.preventDefault()
      submit('CHECK')
    }
  }

  const pick = (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = event.target.files?.[0]
    event.target.value = ''
    if (!chosen) {
      return
    }
    if (chosen.size > MAX_FILE_BYTES) {
      toasts.error(t('screen.chat.fileTooLarge'))
      return
    }
    if (!ACCEPTED.includes(chosen.type)) {
      toasts.error(t('screen.chat.fileType'))
      return
    }
    setFile(chosen)
  }

  return (
    <div className="flex flex-col gap-1.5">
      {file && (
        <p className="flex w-fit items-center gap-2 rounded-md bg-muted px-2 py-1 text-caption">
          <Paperclip aria-hidden="true" className="size-4" />
          <span className="break-all">{file.name}</span>
          <span className="text-muted-foreground">{formatSize(file.size)}</span>
          <button
            type="button"
            aria-label={t('screen.chat.removeFile', { name: file.name })}
            onClick={() => setFile(undefined)}
            className="rounded p-0.5 text-muted-foreground hover:bg-background"
          >
            <X aria-hidden="true" className="size-4" />
          </button>
        </p>
      )}
      <div className="flex items-end gap-2">
        <Button
          type="button"
          variant="ghost"
          className="px-2"
          aria-label={t('screen.chat.attach')}
          onClick={() => fileInput.current?.click()}
        >
          <Paperclip aria-hidden="true" />
        </Button>
        <input ref={fileInput} type="file" accept={ACCEPTED.join(',')} className="hidden" onChange={pick} tabIndex={-1} />
        <textarea
          ref={text}
          id={`${ids}-message`}
          rows={1}
          value={body}
          maxLength={4000}
          onChange={(event) => setBody(event.target.value)}
          onKeyDown={onKeyDown}
          aria-label={t('screen.chat.message')}
          aria-describedby={`${ids}-hint`}
          placeholder={placeholder}
          className="min-h-11 grow resize-none rounded-md border border-input bg-background px-3 py-2.5 text-body-sm focus-visible:outline-2 focus-visible:outline-ring"
        />
        <Button
          type="button"
          aria-label={t('screen.chat.send')}
          className="px-3.5"
          disabled={pending || (body.trim() === '' && !file)}
          onClick={() => submit('CHECK')}
        >
          <Send aria-hidden="true" />
        </Button>
      </div>
      <p id={`${ids}-hint`} className="text-caption text-muted-foreground">
        {t(touch ? 'screen.chat.hintTouch' : 'screen.chat.hintDesktop')}
      </p>
      {warning && (
        <SensitiveDialog
          matches={warning}
          pending={pending}
          onMask={() => submit('MASK')}
          onSend={() => submit('SEND')}
          onEdit={() => {
            setWarning(undefined)
            text.current?.focus()
          }}
        />
      )}
    </div>
  )
}

/**
 * The Sensitive-content warning (FR-47, UX-DR40): each match masked, "Che và gửi" first and by
 * default (it stores the masked text and keeps no original), "Sửa lại", and "Vẫn gửi".
 */
function SensitiveDialog({
  matches,
  pending,
  onMask,
  onEdit,
  onSend,
}: {
  matches: SensitiveMatch[]
  pending: boolean
  onMask: () => void
  onEdit: () => void
  onSend: () => void
}) {
  const mask = useRef<HTMLButtonElement>(null)
  const credential = matches.some((match) => match.kind === 'CREDENTIAL')
  return (
    <Dialog.Root open onOpenChange={(open) => !open && onEdit()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup
          initialFocus={mask}
          className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md"
        >
          <Dialog.Title className="text-h2">
            {t(credential ? 'screen.chat.warning.titleCredential' : 'screen.chat.warning.titlePersonal')}
          </Dialog.Title>
          <div className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4 text-body-sm">
            <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
            <div className="flex flex-col gap-1.5">
              <p>{t('screen.chat.warning.found')}</p>
              <ul className="flex flex-col gap-1">
                {matches.map((match, index) => (
                  <li key={`${match.masked}-${index}`} className="font-mono break-all">
                    {match.masked}
                  </li>
                ))}
              </ul>
              <Dialog.Description>
                {t(credential ? 'screen.chat.warning.bodyCredential' : 'screen.chat.warning.bodyPersonal')}
              </Dialog.Description>
            </div>
          </div>
          <div className="flex flex-wrap justify-between gap-3">
            <Button type="button" variant="ghost" disabled={pending} onClick={onSend}>
              {t('screen.chat.warning.send')}
            </Button>
            <span className="flex flex-wrap gap-3">
              <Button type="button" variant="secondary" onClick={onEdit}>
                {t('screen.chat.warning.edit')}
              </Button>
              <Button ref={mask} type="button" disabled={pending} onClick={onMask}>
                {t('screen.chat.warning.mask')}
              </Button>
            </span>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
