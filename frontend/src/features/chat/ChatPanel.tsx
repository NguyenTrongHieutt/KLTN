import { Lock, Paperclip, Send, X } from 'lucide-react'
import { Fragment, useEffect, useId, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react'
import { MessageText } from '@/components/MessageText'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { ReportAbuseMenu } from '@/features/abuse/ReportAbuseMenu'
import { t } from '@/i18n/t'
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
 * The conversation of JOB-4 and JOB-8 (FR-46, FR-77, UX-DR25, UX-DR40, UX-DR80): the newest 50
 * messages, "Xem tin cũ hơn", day separators, own messages on primary-soft bubbles, and the composer,
 * or a note in its place once the Chat is read-only. A message is sent as written, with no check of
 * what it says (Story 24.4a). Text is shown as text, never as markup, and each `https` link with its
 * domain ({@link MessageText}).
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
      className="flex w-full max-w-reading-max flex-col gap-3.5 rounded-lg border border-border bg-card p-4 md:p-5"
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
        {message.body && (
          <p className="break-words whitespace-pre-wrap">
            <MessageText text={message.body} />
          </p>
        )}
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

  const submit = () => {
    const done = {
      onSuccess: () => {
        setBody('')
        setFile(undefined)
        text.current?.focus()
      },
      onError: (error: unknown) => toasts.error(problemMessage(error, { module: 'chat' }).text),
    }
    if (file) {
      attach.mutate({ file, body: body.trim() }, done)
    } else if (body.trim() !== '') {
      send.mutate({ body: body.trim() }, done)
    }
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !touch && !event.nativeEvent.isComposing) {
      event.preventDefault()
      submit()
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
          onClick={submit}
        >
          <Send aria-hidden="true" />
        </Button>
      </div>
      <p id={`${ids}-hint`} className="text-caption text-muted-foreground">
        {t(touch ? 'screen.chat.hintTouch' : 'screen.chat.hintDesktop')}
      </p>
    </div>
  )
}
