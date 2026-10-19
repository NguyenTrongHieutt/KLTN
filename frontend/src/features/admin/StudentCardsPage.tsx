import { Dialog } from '@base-ui/react/dialog'
import { Menu } from '@base-ui/react/menu'
import { ArrowDown, ArrowUp, Ellipsis, FileText, X } from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { z } from 'zod'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { verificationMessage } from '@/features/identity/verificationMessages'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { zodFieldErrors } from '@/lib/formErrors'
import { cn } from '@/lib/utils'
import {
  useApproveStudentCard,
  useRejectStudentCard,
  useStudentCards,
  type StudentCardQuery,
  type StudentCardRow,
  type StudentCardStatus,
} from './adminApi'

const STATUSES: StudentCardStatus[] = ['PENDING', 'APPROVED', 'REJECTED']

const DEFAULT_SIZE = 20

const REASONS: MessageKey[] = [
  'screen.admin.studentCards.reasonBlurry',
  'screen.admin.studentCards.reasonExpired',
  'screen.admin.studentCards.reasonMissing',
  'screen.admin.studentCards.reasonNotCard',
]

const approval = z.object({
  studentNumber: z.string().trim().min(1, 'required').max(30, 'tooLong'),
  school: z.string().trim().min(1, 'required').max(200, 'tooLong'),
})

/** A pending queue reads oldest first; a decided one, newest decision first. */
function defaultSort(status: StudentCardStatus) {
  return status === 'PENDING' ? 'uploadedAt,asc' : 'decidedAt,desc'
}

/** The URL's query (`status`, `page` from 1, `size`, `sort`), with anything unknown as its default. */
function readQuery(params: URLSearchParams): StudentCardQuery {
  const status = STATUSES.find((candidate) => candidate === params.get('status')) ?? 'PENDING'
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1)
  const size = Math.min(100, Math.max(1, Number.parseInt(params.get('size') ?? `${DEFAULT_SIZE}`, 10) || DEFAULT_SIZE))
  const requested = params.get('sort') ?? ''
  const sort = /^(uploadedAt|decidedAt),(asc|desc)$/.test(requested) ? requested : defaultSort(status)
  return { status, page: page - 1, size, sort }
}

function studentName(row: StudentCardRow) {
  return row.realName ?? row.githubLogin ?? t('screen.admin.studentCards.student')
}

/**
 * ADM-3 (`/quan-tri/the-sinh-vien`, FR-5, UX-DR46): the student cards by status in a Data table,
 * paged on the server with `page`, `size`, and `sort` in the URL, as cards below 768 px. "Xem thẻ"
 * opens the card, a read the server audits (FR-63), with the approval form; "Từ chối" asks for the
 * reason the Student reads.
 */
export function StudentCardsPage() {
  usePageTitle(t('screen.admin.studentCards.title'))
  const [params, setParams] = useSearchParams()
  const query = readQuery(params)
  const cards = useStudentCards(query)
  const [viewing, setViewing] = useState<StudentCardRow>()
  const [rejecting, setRejecting] = useState<StudentCardRow>()

  function update(next: Partial<{ status: StudentCardStatus; page: number; sort: string }>) {
    const merged = { status: query.status, page: query.page, sort: query.sort, ...next }
    const search = new URLSearchParams()
    search.set('status', merged.status)
    search.set('page', `${merged.page + 1}`)
    search.set('size', `${query.size}`)
    search.set('sort', merged.sort)
    setParams(search)
  }

  const rows = cards.data?.items ?? []
  const pages = Math.max(1, Math.ceil((cards.data?.total ?? 0) / query.size))
  const byUpload = query.sort.startsWith('uploadedAt')
  const ascending = query.sort === 'uploadedAt,asc'
  const sortLabel: MessageKey = !byUpload
    ? 'screen.admin.studentCards.sortBy'
    : ascending
      ? 'screen.admin.studentCards.sortOldest'
      : 'screen.admin.studentCards.sortNewest'

  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.admin.studentCards.heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.admin.studentCards.lead')}</p>
      </div>
      <div role="group" aria-label={t('screen.admin.studentCards.filter')} className="flex flex-wrap gap-2">
        {STATUSES.map((status) => (
          <button
            key={status}
            type="button"
            aria-pressed={query.status === status}
            onClick={() => update({ status, page: 0, sort: defaultSort(status) })}
            className={cn(
              'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              query.status === status
                ? 'border-primary bg-primary-soft text-foreground'
                : 'border-border bg-card text-muted-foreground hover:bg-muted',
            )}
          >
            {t(`screen.admin.studentCards.${status}` as MessageKey)}
          </button>
        ))}
      </div>
      <RegionState query={cards} skeleton={<Skeleton className="h-48 w-full" />}>
        {rows.length === 0 ? (
          <p className="rounded-lg border border-border bg-card p-6 text-body-sm text-muted-foreground">
            {t(`screen.admin.studentCards.empty${query.status}` as MessageKey)}
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <div className="hidden overflow-x-auto rounded-lg border border-border bg-card md:block">
              <table className="w-full text-left text-body-sm" aria-label={t('screen.admin.studentCards.listLabel')}>
                <thead className="bg-muted text-caption text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-4 py-2 font-semibold">
                      {t('screen.admin.studentCards.student')}
                    </th>
                    <th scope="col" className="px-4 py-2 font-semibold">
                      {t('screen.admin.studentCards.school')}
                    </th>
                    <th
                      scope="col"
                      aria-sort={byUpload ? (ascending ? 'ascending' : 'descending') : 'none'}
                      className="px-4 py-2 font-semibold"
                    >
                      <button
                        type="button"
                        onClick={() => update({ sort: ascending ? 'uploadedAt,desc' : 'uploadedAt,asc', page: 0 })}
                        aria-label={t(sortLabel)}
                        className="inline-flex items-center gap-1 rounded-sm hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring"
                      >
                        {t('screen.admin.studentCards.uploadedAt')}
                        {byUpload &&
                          (ascending ? (
                            <ArrowUp aria-hidden="true" className="size-3.5" />
                          ) : (
                            <ArrowDown aria-hidden="true" className="size-3.5" />
                          ))}
                      </button>
                    </th>
                    <th scope="col" className="px-4 py-2 font-semibold">
                      {t('screen.admin.studentCards.file')}
                    </th>
                    <th scope="col" className="px-4 py-2 font-semibold">
                      {t('screen.admin.studentCards.status')}
                    </th>
                    <th scope="col" className="px-4 py-2 font-semibold">
                      <span className="sr-only">{t('screen.admin.studentCards.actions')}</span>
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {rows.map((row) => (
                    <tr key={row.id} className="h-11">
                      <td className="px-4 py-1.5">
                        <span className="font-semibold">{studentName(row)}</span>
                        {row.githubLogin && row.realName && (
                          <span className="block text-caption text-muted-foreground">@{row.githubLogin}</span>
                        )}
                      </td>
                      <td className="px-4 py-1.5">{row.profileSchool ?? '—'}</td>
                      <td className="px-4 py-1.5">{formatDayMonthTime(row.uploadedAt)}</td>
                      <td className="px-4 py-1.5">
                        <FileLabel row={row} />
                      </td>
                      <td className="px-4 py-1.5">
                        <Decision row={row} />
                      </td>
                      <td className="px-4 py-1.5 text-right">
                        <RowActions row={row} onView={setViewing} onReject={setRejecting} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="flex flex-col gap-3 md:hidden" aria-label={t('screen.admin.studentCards.listLabel')}>
              {rows.map((row) => (
                <li key={row.id} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="font-semibold">{studentName(row)}</p>
                      <p className="text-caption text-muted-foreground">{row.profileSchool ?? '—'}</p>
                    </div>
                    <RowActions row={row} onView={setViewing} onReject={setRejecting} />
                  </div>
                  <p className="text-body-sm">
                    {t('screen.admin.studentCards.uploadedAt')}: {formatDayMonthTime(row.uploadedAt)} · <FileLabel row={row} />
                  </p>
                  <Decision row={row} />
                </li>
              ))}
            </ul>
            <nav aria-label={t('screen.admin.studentCards.pagination')} className="flex items-center justify-end gap-3">
              <Button
                variant="secondary"
                size="compact"
                disabled={query.page === 0}
                onClick={() => update({ page: query.page - 1 })}
              >
                {t('screen.admin.studentCards.previous')}
              </Button>
              <span className="text-body-sm">{t('screen.admin.studentCards.page', { page: query.page + 1, pages })}</span>
              <Button
                variant="secondary"
                size="compact"
                disabled={query.page + 1 >= pages}
                onClick={() => update({ page: query.page + 1 })}
              >
                {t('screen.admin.studentCards.next')}
              </Button>
            </nav>
          </div>
        )}
      </RegionState>
      <CardDialog
        row={viewing}
        onClose={() => setViewing(undefined)}
        onReject={(row) => {
          setViewing(undefined)
          setRejecting(row)
        }}
      />
      <RejectDialog row={rejecting} onClose={() => setRejecting(undefined)} />
    </div>
  )
}

function FileLabel({ row }: { row: StudentCardRow }) {
  const kind = row.mediaType === 'application/pdf' ? t('screen.admin.studentCards.pdf') : t('screen.admin.studentCards.image')
  return row.fileUrl ? (
    <span>{kind}</span>
  ) : (
    <span className="text-muted-foreground">
      {kind} · {t('screen.admin.studentCards.fileDeleted')}
    </span>
  )
}

function Decision({ row }: { row: StudentCardRow }) {
  if (row.status === 'PENDING') {
    return (
      <span className="rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold text-warning">
        {t('screen.admin.studentCards.PENDING')}
      </span>
    )
  }
  return (
    <span className="flex flex-col">
      <span
        className={cn(
          'w-fit rounded-full px-2 py-0.5 text-caption font-semibold',
          row.status === 'APPROVED' ? 'bg-success-soft text-success' : 'bg-destructive-soft text-destructive',
        )}
      >
        {t(`screen.admin.studentCards.${row.status}` as MessageKey)}
        {row.decidedAt && ` · ${formatDayMonthTime(row.decidedAt)}`}
      </span>
      <span className="text-caption text-muted-foreground">
        {row.status === 'APPROVED'
          ? t('screen.admin.studentCards.recorded', { number: row.studentNumber ?? '', school: row.school ?? '' })
          : row.rejectionReason}
      </span>
    </span>
  )
}

function RowActions({
  row,
  onView,
  onReject,
}: {
  row: StudentCardRow
  onView: (row: StudentCardRow) => void
  onReject: (row: StudentCardRow) => void
}) {
  const itemClass =
    'flex min-h-control-compact cursor-pointer items-center rounded-sm px-3 text-body-sm data-disabled:cursor-default data-disabled:opacity-50 data-highlighted:bg-muted'
  return (
    <Menu.Root>
      <Menu.Trigger
        aria-label={t('screen.admin.studentCards.rowActions', { name: studentName(row) })}
        className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'px-2')}
      >
        <Ellipsis aria-hidden="true" />
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner align="end" sideOffset={4} className="z-50">
          <Menu.Popup className="min-w-40 rounded-md border border-border bg-popover p-1 text-popover-foreground shadow-md">
            <Menu.Item className={itemClass} disabled={!row.fileUrl} onClick={() => onView(row)}>
              {t('screen.admin.studentCards.view')}
            </Menu.Item>
            {row.status === 'PENDING' && (
              <>
                <Menu.Item className={itemClass} disabled={!row.fileUrl} onClick={() => onView(row)}>
                  {t('screen.admin.studentCards.approve')}
                </Menu.Item>
                <Menu.Item className={cn(itemClass, 'text-destructive')} onClick={() => onReject(row)}>
                  {t('screen.admin.studentCards.reject')}
                </Menu.Item>
              </>
            )}
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}

function DialogFrame({
  open,
  onClose,
  title,
  children,
}: {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-12 left-1/2 z-50 flex max-h-[calc(100dvh-96px)] w-[min(640px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-4">
            <Dialog.Title className="text-h2">{title}</Dialog.Title>
            <Dialog.Close
              aria-label={t('screen.admin.studentCards.close')}
              className={cn(buttonVariants({ variant: 'ghost', size: 'compact' }), 'px-2')}
            >
              <X aria-hidden="true" />
            </Dialog.Close>
          </div>
          {children}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** "Xem thẻ": the card itself, and for a pending one the approval form. */
function CardDialog({
  row,
  onClose,
  onReject,
}: {
  row?: StudentCardRow
  onClose: () => void
  onReject: (row: StudentCardRow) => void
}) {
  return (
    <DialogFrame
      open={row !== undefined}
      onClose={onClose}
      title={row ? t('screen.admin.studentCards.viewTitle', { name: studentName(row) }) : ''}
    >
      {row && <CardView key={row.id} row={row} onDone={onClose} onReject={onReject} />}
    </DialogFrame>
  )
}

function CardView({
  row,
  onDone,
  onReject,
}: {
  row: StudentCardRow
  onDone: () => void
  onReject: (row: StudentCardRow) => void
}) {
  const [values, setValues] = useState({ studentNumber: '', school: row.profileSchool ?? '' })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [refusal, setRefusal] = useState<string>()
  const approve = useApproveStudentCard()
  const toasts = useToasts()

  function submit(event: FormEvent) {
    event.preventDefault()
    const result = approval.safeParse(values)
    if (!result.success) {
      setErrors(zodFieldErrors(result.error))
      return
    }
    setErrors({})
    approve.mutate(
      { cardId: row.id, body: result.data },
      {
        onSuccess: () => {
          toasts.success(t('screen.admin.studentCards.approvedToast', { name: studentName(row) }))
          onDone()
        },
        onError: (error) => setRefusal(verificationMessage(error)),
      },
    )
  }

  return (
    <>
      <p className="text-caption text-muted-foreground">{t('screen.admin.studentCards.viewAudited')}</p>
      {row.fileUrl ? (
        row.mediaType === 'application/pdf' ? (
          <a href={row.fileUrl} download className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit')}>
            <FileText aria-hidden="true" />
            {t('screen.admin.studentCards.downloadPdf')}
          </a>
        ) : (
          <img
            src={row.fileUrl}
            alt={t('screen.admin.studentCards.imageAlt', { name: studentName(row) })}
            className="max-h-[50vh] w-full rounded-md border border-border object-contain"
          />
        )
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.admin.studentCards.fileDeleted')}</p>
      )}
      {row.status === 'PENDING' && (
        <form noValidate onSubmit={submit} className="flex flex-col gap-4">
          {refusal && (
            <p role="alert" className="rounded-md border-l-4 border-destructive bg-destructive-soft p-3 text-body-sm">
              {refusal}
            </p>
          )}
          <div className="grid gap-4 md:grid-cols-2">
            <FormField id="cardNumber" label={t('screen.admin.studentCards.studentNumber')} error={errors.studentNumber}>
              <input
                {...fieldProps('cardNumber', errors.studentNumber)}
                className={inputClass}
                maxLength={30}
                value={values.studentNumber}
                onChange={(event) => setValues({ ...values, studentNumber: event.target.value })}
              />
            </FormField>
            <FormField
              id="cardSchool"
              label={t('screen.admin.studentCards.schoolField')}
              helper={t('screen.admin.studentCards.schoolHelper')}
              error={errors.school}
            >
              <input
                {...fieldProps('cardSchool', errors.school, t('screen.admin.studentCards.schoolHelper'))}
                className={inputClass}
                maxLength={200}
                value={values.school}
                onChange={(event) => setValues({ ...values, school: event.target.value })}
              />
            </FormField>
          </div>
          <div className="flex flex-wrap justify-between gap-3">
            <Button type="button" variant="secondary" onClick={() => onReject(row)}>
              {t('screen.admin.studentCards.reject')}
            </Button>
            <Button type="submit" disabled={approve.isPending}>
              {approve.isPending ? t('screen.admin.studentCards.approving') : t('screen.admin.studentCards.approveSubmit')}
            </Button>
          </div>
        </form>
      )}
    </>
  )
}

/** "Từ chối thẻ của {name}?": a preset reason or one of the Administrator's own, then what happens. */
function RejectDialog({ row, onClose }: { row?: StudentCardRow; onClose: () => void }) {
  return (
    <DialogFrame
      open={row !== undefined}
      onClose={onClose}
      title={row ? t('screen.admin.studentCards.rejectTitle', { name: studentName(row) }) : ''}
    >
      {row && <RejectForm key={row.id} row={row} onDone={onClose} />}
    </DialogFrame>
  )
}

function RejectForm({ row, onDone }: { row: StudentCardRow; onDone: () => void }) {
  const [choice, setChoice] = useState<MessageKey | 'other'>(REASONS[0])
  const [other, setOther] = useState('')
  const [error, setError] = useState<string>()
  const [refusal, setRefusal] = useState<string>()
  const reject = useRejectStudentCard()
  const toasts = useToasts()

  function submit(event: FormEvent) {
    event.preventDefault()
    const reason = choice === 'other' ? other.trim() : t(choice)
    if (reason.length === 0) {
      setError(t('form.required'))
      return
    }
    if (reason.length > 300) {
      setError(t('form.tooLong'))
      return
    }
    setError(undefined)
    reject.mutate(
      { cardId: row.id, body: { reason } },
      {
        onSuccess: () => {
          toasts.success(t('screen.admin.studentCards.rejectedToast', { name: studentName(row) }))
          onDone()
        },
        onError: (failure) => setRefusal(verificationMessage(failure)),
      },
    )
  }

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4">
      {refusal && (
        <p role="alert" className="rounded-md border-l-4 border-destructive bg-destructive-soft p-3 text-body-sm">
          {refusal}
        </p>
      )}
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-label">{t('screen.admin.studentCards.reasonLabel')}</legend>
        {[...REASONS, 'other' as const].map((reason) => (
          <label key={reason} className="flex min-h-control-compact items-center gap-2 text-body-sm">
            <input
              type="radio"
              name="rejectReason"
              value={reason}
              checked={choice === reason}
              onChange={() => setChoice(reason)}
              className="size-4 accent-primary"
            />
            {reason === 'other' ? t('screen.admin.studentCards.reasonOther') : t(reason)}
          </label>
        ))}
      </fieldset>
      {choice === 'other' && (
        <FormField id="rejectOther" label={t('screen.admin.studentCards.reasonOtherLabel')} error={error}>
          <textarea
            {...fieldProps('rejectOther', error)}
            className={cn(inputClass, 'min-h-24')}
            maxLength={300}
            value={other}
            onChange={(event) => setOther(event.target.value)}
          />
        </FormField>
      )}
      <div className="flex flex-col gap-1">
        <p className="text-label font-semibold">{t('screen.admin.studentCards.whatHappens')}</p>
        <ul className="list-disc pl-5 text-body-sm">
          <li>{t('screen.admin.studentCards.happens1', { name: studentName(row) })}</li>
          <li>{t('screen.admin.studentCards.happens2')}</li>
          <li>{t('screen.admin.studentCards.happens3')}</li>
        </ul>
      </div>
      <div className="flex flex-wrap justify-between gap-3">
        <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.studentCards.back')}</Dialog.Close>
        <Button type="submit" variant="destructive" disabled={reject.isPending}>
          {reject.isPending ? t('screen.admin.studentCards.rejecting') : t('screen.admin.studentCards.rejectSubmit')}
        </Button>
      </div>
    </form>
  )
}
