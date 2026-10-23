import { Dialog } from '@base-ui/react/dialog'
import { useId, useState, type FormEvent } from 'react'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { AbuseReportsPanel } from './AbuseReportsPanel'
import { useHideContent, useModerationItems, type ContentKind, type ModerationItem } from './adminModerationApi'

const KINDS: ContentKind[] = ['PRODUCT', 'JOB_POST', 'PAGE', 'RATING']

type Tab = ContentKind | 'REPORTS'

const TABS: Tab[] = [...KINDS, 'REPORTS']

/**
 * ADM-6 (`/quan-tri/kiem-duyet`, FR-60, FR-69, UX-DR16): per kind, the newest visible or hidden items with
 * their author, and "Ẩn" with the reason the author reads. Hiding never deletes, and running Jobs continue.
 * The "Báo cáo vi phạm" tab holds the Abuse Report queue (Story 16.1).
 */
export function AdminModerationPage() {
  usePageTitle(t('screen.admin.moderation.title'))
  const [tab, setTab] = useState<Tab>('PRODUCT')

  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.moderation.heading')}</PageHeading>
      <div role="tablist" aria-label={t('screen.admin.moderation.kinds')} className="flex flex-wrap gap-2">
        {TABS.map((option) => (
          <button
            key={option}
            type="button"
            role="tab"
            aria-selected={tab === option}
            onClick={() => setTab(option)}
            className={cn(
              'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              tab === option ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
            )}
          >
            {t(`screen.admin.moderation.kind.${option}` as MessageKey)}
          </button>
        ))}
      </div>
      {tab === 'REPORTS' ? <AbuseReportsPanel /> : <ModerationList key={tab} kind={tab} />}
    </div>
  )
}

/** One FR-60 kind's newest visible or hidden items, and "Ẩn". */
function ModerationList({ kind }: { kind: ContentKind }) {
  const [hidden, setHidden] = useState(false)
  const items = useModerationItems(kind, hidden)
  const [hiding, setHiding] = useState<ModerationItem>()

  return (
    <>
      <div role="group" aria-label={t('screen.admin.moderation.show')} className="flex flex-wrap gap-2">
        {[false, true].map((option) => (
          <button
            key={String(option)}
            type="button"
            aria-pressed={hidden === option}
            onClick={() => setHidden(option)}
            className={cn(
              'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              hidden === option ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
            )}
          >
            {t(option ? 'screen.admin.moderation.hidden' : 'screen.admin.moderation.visible')}
          </button>
        ))}
      </div>
      <RegionState query={items} skeleton={<Skeleton className="h-64 w-full" />}>
        {items.data &&
          (items.data.length === 0 ? (
            <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">
              {t('screen.admin.moderation.empty')}
            </p>
          ) : (
            <ul
              aria-label={t('screen.admin.moderation.listLabel')}
              className="flex flex-col divide-y divide-border overflow-hidden rounded-lg border border-border bg-card"
            >
              {items.data.map((item) => (
                <li key={item.id} className="flex flex-wrap items-start justify-between gap-3 p-4 text-body-sm">
                  <div className="flex min-w-0 grow flex-col gap-1">
                    <span className="text-label font-semibold break-words">{item.title}</span>
                    {item.excerpt && <span className="line-clamp-2 break-words text-muted-foreground">{item.excerpt}</span>}
                    <span className="text-caption text-muted-foreground">
                      {[item.authorName, formatDayMonthTime(item.createdAt)].filter(Boolean).join(' · ')}
                    </span>
                    {item.hiddenReason && (
                      <span className="text-caption text-destructive break-words">
                        {t('screen.admin.moderation.hiddenBecause', { reason: item.hiddenReason })}
                      </span>
                    )}
                  </div>
                  {!item.hiddenAt && (
                    <Button type="button" variant="destructive" size="compact" onClick={() => setHiding(item)}>
                      {t('screen.admin.moderation.hide')}
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          ))}
      </RegionState>
      <HideDialog kind={kind} item={hiding} onClose={() => setHiding(undefined)} />
    </>
  )
}

function HideDialog({ kind, item, onClose }: { kind: ContentKind; item?: ModerationItem; onClose: () => void }) {
  const toasts = useToasts()
  const hide = useHideContent(kind)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const reasonId = useId()

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!item || reason.trim() === '') {
      return
    }
    hide.mutate(
      { contentId: item.id, reason: reason.trim() },
      {
        onSuccess: () => {
          toasts.success(t('screen.admin.moderation.done'))
          setReason('')
          setError(undefined)
          onClose()
        },
        onError: (failure) => setError(problemMessage(failure, { module: 'admin' }).text),
      },
    )
  }

  return (
    <Dialog.Root open={item !== undefined} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100dvh-96px)] w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          {item && (
            <form onSubmit={submit} className="flex flex-col gap-4">
              <Dialog.Title className="text-h2 break-words">
                {t('screen.admin.moderation.ask', { title: item.title })}
              </Dialog.Title>
              <p className="text-body-sm">{t(`screen.admin.moderation.effects.${kind}` as MessageKey)}</p>
              <label htmlFor={reasonId} className="text-label">
                {t('screen.admin.moderation.reason')}
              </label>
              <textarea
                id={reasonId}
                value={reason}
                maxLength={500}
                required
                onChange={(event) => setReason(event.target.value)}
                className={cn(inputClass, 'min-h-24')}
              />
              {error && (
                <p role="alert" className="text-body-sm text-destructive">
                  {error}
                </p>
              )}
              <div className="flex flex-wrap justify-end gap-2">
                <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.moderation.back')}</Dialog.Close>
                <Button type="submit" variant="destructive" disabled={hide.isPending || reason.trim() === ''}>
                  {t('screen.admin.moderation.hide')}
                </Button>
              </div>
            </form>
          )}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
