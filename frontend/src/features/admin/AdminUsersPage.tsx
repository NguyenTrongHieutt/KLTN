import { Dialog } from '@base-ui/react/dialog'
import { useId, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDate, formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useAdminUser,
  useAdminUsers,
  useEraseUser,
  useUserCommand,
  type AdminUser,
  type FeedbackCategory,
  type UserFilter,
  type UserQuery,
  type UserRole,
} from './adminUsersApi'
import { StepUpDialog } from './StepUpDialog'

const ROLES: UserRole[] = ['CUSTOMER', 'MENTOR', 'STUDENT']

const FILTERS: UserFilter[] = ['LOCKED', 'VERIFIED', 'PENDING', 'APPROVED', 'REVOKED', 'LOW_FEEDBACK']

const CATEGORIES: FeedbackCategory[] = ['RESPECT', 'GUIDANCE', 'RULES']

const PAGE_SIZE = 20

function readQuery(params: URLSearchParams): UserQuery {
  const role = ROLES.find((candidate) => candidate === params.get('role'))
  const status = FILTERS.find((candidate) => candidate === params.get('status'))
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1) - 1
  return { q: params.get('q') ?? undefined, role, status, page }
}

function nameOf(user: { name?: string; githubLogin?: string }): string {
  return user.name ?? (user.githubLogin ? `@${user.githubLogin}` : t('screen.admin.users.unnamed'))
}

/**
 * ADM-2 (`/quan-tri/nguoi-dung/{accountId?}`, FR-5, FR-59, FR-74, FR-78, UX-DR46, UX-DR69, UX-DR81): the
 * search with role and status chips, and the chosen account with its verification or approval, its
 * lock history, and for a Mentor the private feedback, which only Administrators read. Locking,
 * unlocking, and revoking a verification take a reason; "Thu hồi" a Mentor's approval takes a ground
 * and the authenticator code, and the private-feedback ground offers only FR-78's categories.
 */
export function AdminUsersPage() {
  usePageTitle(t('screen.admin.users.title'))
  const { accountId } = useParams()
  const [params, setParams] = useSearchParams()
  const query = readQuery(params)
  const users = useAdminUsers(query)
  const [text, setText] = useState(query.q ?? '')
  const searchId = useId()

  function update(next: Partial<UserQuery>) {
    const merged = { ...query, ...next }
    const search = new URLSearchParams()
    if (merged.q) search.set('q', merged.q)
    if (merged.role) search.set('role', merged.role)
    if (merged.status) search.set('status', merged.status)
    search.set('page', `${merged.page + 1}`)
    setParams(search)
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    update({ q: text.trim() || undefined, page: 0 })
  }

  const rows = users.data?.items ?? []
  const pages = Math.max(1, Math.ceil((users.data?.total ?? 0) / PAGE_SIZE))
  const search = params.toString() ? `?${params.toString()}` : ''

  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.users.heading')}</PageHeading>
      <form role="search" onSubmit={submit} className="flex flex-wrap items-end gap-2">
        <div className="flex min-w-0 grow flex-col gap-1">
          <label htmlFor={searchId} className="text-label">
            {t('screen.admin.users.search')}
          </label>
          <input
            id={searchId}
            type="search"
            value={text}
            maxLength={100}
            onChange={(event) => setText(event.target.value)}
            className={inputClass}
          />
        </div>
        <Button type="submit">{t('screen.admin.users.find')}</Button>
      </form>
      <Chips
        label={t('screen.admin.users.roleFilter')}
        options={ROLES}
        current={query.role}
        text={(role) => t(`screen.admin.users.role.${role}` as MessageKey)}
        onPick={(role) => update({ role, page: 0 })}
      />
      <Chips
        label={t('screen.admin.users.statusFilter')}
        options={FILTERS}
        current={query.status}
        text={(status) => t(`screen.admin.users.filter.${status}` as MessageKey)}
        onPick={(status) => update({ status, page: 0 })}
      />
      <div className="flex flex-col gap-5 lg:flex-row lg:items-start">
        <aside className="flex flex-col gap-3 lg:w-[320px] lg:shrink-0">
          <RegionState query={users} skeleton={<Skeleton className="h-40 w-full" />}>
            {rows.length === 0 ? (
              <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">
                {t('screen.admin.users.empty')}
              </p>
            ) : (
              <ul
                aria-label={t('screen.admin.users.listLabel')}
                className="flex flex-col divide-y divide-border overflow-hidden rounded-lg border border-border bg-card"
              >
                {rows.map((row) => (
                  <li key={row.id}>
                    <Link
                      to={`/quan-tri/nguoi-dung/${row.id}${search}`}
                      aria-current={row.id === accountId ? 'page' : undefined}
                      className={cn('flex flex-col gap-0.5 p-3 text-body-sm hover:bg-muted', row.id === accountId && 'bg-primary-soft')}
                    >
                      <span className="text-label font-semibold break-words">{nameOf(row)}</span>
                      <span className="text-caption text-muted-foreground">
                        {[
                          t(`screen.admin.users.role.${row.role}` as MessageKey),
                          row.status ? t(`screen.admin.users.status.${row.status}` as MessageKey) : '',
                          row.locked ? t('screen.admin.users.locked') : '',
                        ]
                          .filter((part) => part !== '')
                          .join(' · ')}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </RegionState>
          {pages > 1 && (
            <nav aria-label={t('screen.admin.users.pagination')} className="flex items-center justify-between gap-2">
              <Button variant="secondary" size="compact" disabled={query.page === 0} onClick={() => update({ page: query.page - 1 })}>
                {t('screen.admin.users.previous')}
              </Button>
              <span className="text-caption">{t('screen.admin.users.page', { page: query.page + 1, pages })}</span>
              <Button
                variant="secondary"
                size="compact"
                disabled={query.page + 1 >= pages}
                onClick={() => update({ page: query.page + 1 })}
              >
                {t('screen.admin.users.next')}
              </Button>
            </nav>
          )}
        </aside>
        <div className="flex min-w-0 grow flex-col gap-4">
          {accountId ? (
            <UserPanel key={accountId} accountId={accountId} />
          ) : (
            <p className="rounded-lg border border-dashed border-border bg-card p-6 text-body-sm text-muted-foreground">
              {t('screen.admin.users.pick')}
            </p>
          )}
        </div>
      </div>
    </div>
  )
}

function Chips<T extends string>({
  label,
  options,
  current,
  text,
  onPick,
}: {
  label: string
  options: T[]
  current?: T
  text: (option: T) => string
  onPick: (option: T | undefined) => void
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-2">
      {options.map((option) => (
        <button
          key={option}
          type="button"
          aria-pressed={current === option}
          onClick={() => onPick(current === option ? undefined : option)}
          className={cn(
            'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
            current === option ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
          )}
        >
          {text(option)}
        </button>
      ))}
    </div>
  )
}

type Asking = 'lock' | 'unlock' | 'revoke-verification' | 'revoke-approval'

/** Each command's copy in `vi.json`. */
const COPY: Record<Asking, string> = {
  lock: 'lock',
  unlock: 'unlock',
  'revoke-verification': 'revokeVerification',
  'revoke-approval': 'revokeApproval',
}

function UserPanel({ accountId }: { accountId: string }) {
  const user = useAdminUser(accountId)
  return (
    <RegionState query={user} skeleton={<Skeleton className="h-96 w-full" />}>
      {user.data && <UserDetail user={user.data} />}
    </RegionState>
  )
}

function UserDetail({ user }: { user: AdminUser }) {
  const [asking, setAsking] = useState<Asking>()
  const verified = user.verification?.current === true
  return (
    <>
      <section aria-labelledby="user-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5 text-body-sm">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <h2 id="user-heading" className="text-h2 break-words">
            {nameOf(user)}
          </h2>
          {user.lockedAt && (
            <span className="rounded-full bg-destructive-soft px-2 py-0.5 text-caption font-semibold text-destructive">
              {t('screen.admin.users.locked')}
            </span>
          )}
        </div>
        <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-[max-content_1fr]">
          <dt className="text-muted-foreground">{t('screen.admin.users.roleLabel')}</dt>
          <dd>{t(`screen.admin.users.role.${user.role}` as MessageKey)}</dd>
          {user.githubLogin && (
            <>
              <dt className="text-muted-foreground">{t('screen.admin.users.github')}</dt>
              <dd className="break-words">@{user.githubLogin}</dd>
            </>
          )}
          {user.email && (
            <>
              <dt className="text-muted-foreground">{t('screen.admin.users.email')}</dt>
              <dd className="break-words">{user.email}</dd>
            </>
          )}
          <dt className="text-muted-foreground">{t('screen.admin.users.createdAt')}</dt>
          <dd>{formatDayMonthTime(user.createdAt)}</dd>
          {user.role === 'STUDENT' && (
            <>
              <dt className="text-muted-foreground">{t('screen.admin.users.verification')}</dt>
              <dd>
                {user.verification
                  ? t(verified ? 'screen.admin.users.verifiedUntil' : 'screen.admin.users.verificationExpired', {
                      method: t(`screen.admin.users.method.${user.verification.method}` as MessageKey),
                      date: formatCalendarDate(user.verification.validUntil),
                    })
                  : t('screen.admin.users.unverified')}
              </dd>
            </>
          )}
          {user.approval && (
            <>
              <dt className="text-muted-foreground">{t('screen.admin.users.approval')}</dt>
              <dd>{t(`screen.admin.users.status.${user.approval}` as MessageKey)}</dd>
            </>
          )}
          {user.lockReason && (
            <>
              <dt className="text-muted-foreground">{t('screen.admin.users.lockReason')}</dt>
              <dd className="break-words">{user.lockReason}</dd>
            </>
          )}
        </dl>
        <div className="flex flex-wrap gap-2">
          {user.lockedAt ? (
            <Button type="button" variant="secondary" onClick={() => setAsking('unlock')}>
              {t('screen.admin.users.unlock')}
            </Button>
          ) : (
            <Button type="button" variant="destructive" onClick={() => setAsking('lock')}>
              {t('screen.admin.users.lock')}
            </Button>
          )}
          {user.role === 'STUDENT' && verified && (
            <Button type="button" variant="secondary" onClick={() => setAsking('revoke-verification')}>
              {t('screen.admin.users.revokeVerification')}
            </Button>
          )}
          {user.approval === 'APPROVED' && (
            <Button type="button" variant="secondary" onClick={() => setAsking('revoke-approval')}>
              {t('screen.admin.users.revokeApproval')}
            </Button>
          )}
        </div>
        <p className="text-caption text-muted-foreground">{t('screen.admin.users.unlockNote')}</p>
      </section>
      {user.feedback && (
        <section aria-labelledby="feedback-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5 text-body-sm">
          <h3 id="feedback-heading" className="text-h3">
            {t('screen.admin.users.feedback')}
          </h3>
          <p className="text-caption text-muted-foreground">{t('screen.admin.users.feedbackPrivate')}</p>
          <p className="tabular-nums">
            {t('screen.admin.users.feedbackSummary', { average: user.feedback.average.toLocaleString('vi-VN'), n: user.feedback.count })}
          </p>
          {user.feedback.comments.length > 0 && (
            <ul className="flex flex-col gap-1">
              {user.feedback.comments.map((comment, index) => (
                <li key={index} className="rounded-md bg-muted p-2 break-words whitespace-pre-line">
                  {comment}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      {user.deletionRequest && <DeletionSection user={user} />}
      <section aria-labelledby="history-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5 text-body-sm">
        <h3 id="history-heading" className="text-h3">
          {t('screen.admin.users.history')}
        </h3>
        {user.history.length === 0 ? (
          <p className="text-muted-foreground">{t('screen.admin.users.noHistory')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {user.history.map((action, index) => (
              <li key={index} className="flex flex-col gap-0.5">
                <span className="text-label">
                  {t(`screen.admin.users.action.${action.action}` as MessageKey)} · {formatDayMonthTime(action.actedAt)}
                  {action.actedBy ? ` · ${action.actedBy}` : ''}
                </span>
                <span className="break-words text-muted-foreground">{action.reason}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
      {asking === 'revoke-approval' ? (
        <RevokeApprovalDialog user={user} onClose={() => setAsking(undefined)} />
      ) : (
        <ReasonDialog user={user} action={asking} onClose={() => setAsking(undefined)} />
      )}
    </>
  )
}

/**
 * The person's account deletion request (NFR-11, AD-24): when it was sent, what keeps it waiting (each Job
 * linked to ADM-5), and "Xác nhận xóa tài khoản" behind the step-up once nothing does.
 */
function DeletionSection({ user }: { user: AdminUser }) {
  const toasts = useToasts()
  const navigate = useNavigate()
  const erase = useEraseUser(user.id)
  const [stepUp, setStepUp] = useState(false)
  const [error, setError] = useState<string>()
  const deletion = user.deletionRequest!
  return (
    <section aria-labelledby="deletion-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5 text-body-sm">
      <h3 id="deletion-heading" className="text-h3">
        {t('screen.admin.users.deletion.title')}
      </h3>
      <p>{t('screen.admin.users.deletion.requested', { time: formatDayMonthTime(deletion.requestedAt) })}</p>
      {deletion.blockers.length > 0 ? (
        <div className="flex flex-col gap-1">
          <p className="text-label">{t('screen.admin.users.deletion.waiting')}</p>
          <ul className="flex flex-col gap-1">
            {deletion.blockers.map((blocker, index) => (
              <li key={`${blocker.reason}-${blocker.jobId ?? index}`}>
                {blocker.jobId ? (
                  <Link to={`/quan-tri/cong-viec/${blocker.jobId}`} className="text-primary underline-offset-4 hover:underline">
                    {t(`screen.myData.blocker.${blocker.reason}` as MessageKey)}
                  </Link>
                ) : (
                  t(`screen.myData.blocker.${blocker.reason}` as MessageKey)
                )}
              </li>
            ))}
          </ul>
          <p className="text-caption text-muted-foreground">{t('screen.admin.users.deletion.lockFirst')}</p>
        </div>
      ) : (
        <p className="text-caption text-muted-foreground">{t('screen.admin.users.deletion.effects')}</p>
      )}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      <div>
        <Button type="button" variant="destructive" disabled={deletion.blockers.length > 0} onClick={() => setStepUp(true)}>
          {t('screen.admin.users.deletion.confirm')}
        </Button>
      </div>
      <StepUpDialog
        open={stepUp}
        onOpenChange={setStepUp}
        confirmLabel={t('screen.admin.users.deletion.confirm')}
        pending={erase.isPending}
        onConfirm={(code, refused) =>
          erase.mutate(code, {
            onSuccess: () => {
              toasts.success(t('screen.admin.users.deletion.done'))
              navigate('/quan-tri/nguoi-dung')
            },
            onError: (failure) => {
              refused(failure)
              setError(problemMessage(failure, { module: 'identity' }).text)
            },
          })
        }
      />
    </section>
  )
}

/** Lock, unlock, or revoke a verification: what follows, and the reason the person reads. */
function ReasonDialog({
  user,
  action,
  onClose,
}: {
  user: AdminUser
  action?: Exclude<Asking, 'revoke-approval'>
  onClose: () => void
}) {
  const toasts = useToasts()
  const command = useUserCommand(user.id)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const reasonId = useId()

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!action || reason.trim() === '') {
      return
    }
    command.mutate(
      { action, reason: reason.trim() },
      {
        onSuccess: () => {
          toasts.success(t(`screen.admin.users.done.${COPY[action]}` as MessageKey))
          setReason('')
          onClose()
        },
        onError: (failure) => setError(problemMessage(failure, { module: 'identity' }).text),
      },
    )
  }

  return (
    <Dialog.Root open={action !== undefined} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100dvh-96px)] w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          {action && (
            <form onSubmit={submit} className="flex flex-col gap-4">
              <Dialog.Title className="text-h2 break-words">
                {t(`screen.admin.users.ask.${COPY[action]}` as MessageKey, { name: nameOf(user) })}
              </Dialog.Title>
              <p className="text-body-sm">{t(`screen.admin.users.effects.${COPY[action]}` as MessageKey)}</p>
              <label htmlFor={reasonId} className="text-label">
                {t('screen.admin.users.reason')}
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
                <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.users.back')}</Dialog.Close>
                <Button
                  type="submit"
                  variant={action === 'unlock' ? 'primary' : 'destructive'}
                  disabled={command.isPending || reason.trim() === ''}
                >
                  {t(`screen.admin.users.confirm.${COPY[action]}` as MessageKey)}
                </Button>
              </div>
            </form>
          )}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** "Thu hồi" (FR-74, FR-78): the ground, its reason or category, then the authenticator code. */
function RevokeApprovalDialog({ user, onClose }: { user: AdminUser; onClose: () => void }) {
  const toasts = useToasts()
  const command = useUserCommand(user.id)
  const ids = useId()
  const [ground, setGround] = useState<'OTHER' | 'PRIVATE_FEEDBACK'>('OTHER')
  const [reason, setReason] = useState('')
  const [category, setCategory] = useState<FeedbackCategory>('RESPECT')
  const [stepUp, setStepUp] = useState(false)
  const [error, setError] = useState<string>()
  const enough = user.feedback?.enoughToAct === true
  const ready = ground === 'PRIVATE_FEEDBACK' ? enough : reason.trim() !== ''

  return (
    <>
      <Dialog.Root open={!stepUp} onOpenChange={(next) => !next && onClose()}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100dvh-96px)] w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2 break-words">
              {t('screen.admin.users.ask.revokeApproval', { name: nameOf(user) })}
            </Dialog.Title>
            <p className="text-body-sm">{t('screen.admin.users.effects.revokeApproval')}</p>
            <fieldset className="flex flex-col gap-1">
              <legend className="text-label">{t('screen.admin.users.ground')}</legend>
              {(['OTHER', 'PRIVATE_FEEDBACK'] as const).map((option) => (
                <label key={option} className="flex min-h-tap-min items-center gap-2">
                  <input
                    type="radio"
                    name={`${ids}-ground`}
                    className="size-4"
                    checked={ground === option}
                    onChange={() => setGround(option)}
                  />
                  {t(`screen.admin.users.groundOption.${option}` as MessageKey)}
                </label>
              ))}
            </fieldset>
            {ground === 'OTHER' ? (
              <>
                <label htmlFor={`${ids}-reason`} className="text-label">
                  {t('screen.admin.users.reason')}
                </label>
                <textarea
                  id={`${ids}-reason`}
                  value={reason}
                  maxLength={500}
                  onChange={(event) => setReason(event.target.value)}
                  className={cn(inputClass, 'min-h-24')}
                />
              </>
            ) : (
              <>
                <label htmlFor={`${ids}-category`} className="text-label">
                  {t('screen.admin.users.category')}
                </label>
                <select
                  id={`${ids}-category`}
                  value={category}
                  onChange={(event) => setCategory(event.target.value as FeedbackCategory)}
                  className={inputClass}
                >
                  {CATEGORIES.map((option) => (
                    <option key={option} value={option}>
                      {t(`screen.admin.users.categoryOption.${option}` as MessageKey)}
                    </option>
                  ))}
                </select>
                <p className={cn('text-caption', enough ? 'text-muted-foreground' : 'text-destructive')}>
                  {t(enough ? 'screen.admin.users.categoryNote' : 'reason.admin.FEEDBACK_NOT_ENOUGH')}
                </p>
              </>
            )}
            {error && (
              <p role="alert" className="text-body-sm text-destructive">
                {error}
              </p>
            )}
            <div className="flex flex-wrap justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.users.back')}</Dialog.Close>
              <Button type="button" variant="destructive" disabled={!ready} onClick={() => setStepUp(true)}>
                {t('screen.admin.users.confirm.revokeApproval')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
      <StepUpDialog
        open={stepUp}
        onOpenChange={(next) => {
          setStepUp(next)
        }}
        confirmLabel={t('screen.admin.users.confirm.revokeApproval')}
        pending={command.isPending}
        onConfirm={(code, refused) =>
          command.mutate(
            {
              action: 'revoke-approval',
              body:
                ground === 'OTHER'
                  ? { ground, reason: reason.trim(), stepUpCode: code }
                  : { ground, category, stepUpCode: code },
            },
            {
              onSuccess: () => {
                toasts.success(t('screen.admin.users.done.revokeApproval'))
                onClose()
              },
              onError: (failure) => {
                refused(failure)
                setError(problemMessage(failure, { module: 'admin' }).text)
              },
            },
          )
        }
      />
    </>
  )
}
