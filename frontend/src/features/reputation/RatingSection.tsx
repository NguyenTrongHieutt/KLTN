import { Dialog } from '@base-ui/react/dialog'
import { Star } from 'lucide-react'
import { useId, useState } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useGiveFeedback, useRate, useRatings, type GivenRating, type Ratee, type Ratings } from './ratingApi'

/**
 * JOB-1's Ratings from Closed (FR-49, FR-78, UX-DR27, UX-DR51, UX-DR82): the Customer and the Mentor
 * rate each person they rate, with five stars and a comment that cannot be changed once sent; until the
 * other side rated or the window ended their Rating stays hidden, and the section says when it shows. A
 * Crew member who was ever active reads the Mentor's Rating of them after the end, and may give private
 * feedback about the Mentor that only Administrators read (N-21).
 */
export function RatingSection({ jobId }: { jobId: string }) {
  const ratings = useRatings(jobId)
  // An auxiliary part of the overview: nothing while loading, when the read fails, or before the window.
  return ratings.data?.openedAt ? <Content jobId={jobId} ratings={ratings.data} /> : null
}

function Content({ jobId, ratings }: { jobId: string; ratings: Ratings }) {
  const ids = useId()
  const crew = ratings.audience === 'CREW'
  const endsAt = ratings.endsAt ?? ratings.openedAt ?? ''
  const given = ratings.ratings.filter((rating) => rating.mine)
  const received = ratings.ratings.filter((rating) => !rating.mine)
  return (
    <section
      aria-labelledby={`${ids}-title`}
      className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5"
    >
      <h2 id={`${ids}-title`} className="text-h2">
        {t('screen.rating.heading')}
      </h2>
      {ratings.open && ratings.toRate.length > 0 && (
        <p className="text-body-sm">{t('screen.rating.intro', { date: formatDayMonth(endsAt) })}</p>
      )}
      {!ratings.open && !crew && <p className="text-body-sm text-muted-foreground">{t('screen.rating.closed')}</p>}
      {ratings.open && ratings.toRate.map((ratee) => <RatingForm key={ratee.accountId} jobId={jobId} ratee={ratee} />)}
      {ratings.hiddenUntil && (
        <p className="rounded-md bg-muted p-3 text-body-sm">
          {t(crew ? 'screen.rating.hiddenEnd' : 'screen.rating.hiddenPair', {
            date: formatDayMonth(ratings.hiddenUntil),
          })}
        </p>
      )}
      {given.length > 0 && <RatingList title={t('screen.rating.given')} ratings={given} mine />}
      {received.length > 0 && <RatingList title={t('screen.rating.received')} ratings={received} mine={false} />}
      {crew && <PrivateFeedback jobId={jobId} ratings={ratings} />}
    </section>
  )
}

function rateeLabel(ratee: Ratee): string {
  const key =
    ratee.role === 'MENTOR'
      ? 'screen.rating.rateMentor'
      : ratee.role === 'CUSTOMER'
        ? 'screen.rating.rateCustomer'
        : 'screen.rating.rateCrew'
  return t(key, { name: ratee.name })
}

/** Five 32 px stars as one radio group (UX-DR27). */
function Stars({
  value,
  onChange,
  labelledBy,
}: {
  value: number
  onChange: (stars: number) => void
  labelledBy: string
}) {
  return (
    <div role="radiogroup" aria-labelledby={labelledBy} className="flex gap-1">
      {[1, 2, 3, 4, 5].map((stars) => (
        <button
          key={stars}
          type="button"
          role="radio"
          aria-checked={value === stars}
          aria-label={t('screen.rating.star', { n: stars })}
          onClick={() => onChange(stars)}
          className="flex size-tap-min items-center justify-center rounded-md focus-visible:outline-2 focus-visible:outline-ring"
        >
          <Star
            aria-hidden="true"
            className={cn('size-8', stars <= value ? 'fill-warning text-warning' : 'text-muted-foreground')}
          />
        </button>
      ))}
    </div>
  )
}

function RatingForm({ jobId, ratee }: { jobId: string; ratee: Ratee }) {
  const ids = useId()
  const toasts = useToasts()
  const rate = useRate(jobId)
  const [stars, setStars] = useState(0)
  const [comment, setComment] = useState('')
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<string>()
  const ready = stars > 0 && comment.trim() !== ''
  const send = () => {
    setError(undefined)
    rate.mutate(
      { rateeId: ratee.accountId, stars, comment: comment.trim() },
      {
        onSuccess: () => {
          setAsking(false)
          toasts.success(t('screen.rating.sent'))
        },
        onError: (failure) => {
          setAsking(false)
          setError(problemMessage(failure, { module: 'reputation' }).text)
        },
      },
    )
  }
  return (
    <fieldset className="flex flex-col gap-2 rounded-md border border-border bg-background p-3">
      <legend className="px-1 text-label">{rateeLabel(ratee)}</legend>
      <span id={`${ids}-stars`} className="text-body-sm">
        {t('screen.rating.stars')}
      </span>
      <Stars value={stars} onChange={setStars} labelledBy={`${ids}-stars`} />
      <label htmlFor={`${ids}-comment`} className="text-label">
        {t('screen.rating.comment')}
      </label>
      <textarea
        id={`${ids}-comment`}
        value={comment}
        maxLength={2000}
        aria-describedby={`${ids}-hint`}
        onChange={(event) => setComment(event.target.value)}
        className={cn(inputClass, 'min-h-18')}
      />
      <p id={`${ids}-hint`} className="text-caption text-muted-foreground">
        {t('screen.rating.commentHint')} {t('screen.rating.final')}
      </p>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button type="button" disabled={!ready || rate.isPending} onClick={() => setAsking(true)}>
          {t('screen.rating.submit')}
        </Button>
      </div>
      <Confirm
        open={asking}
        title={t('screen.rating.confirmTitle', { name: ratee.name })}
        action={t('screen.rating.submit')}
        pending={rate.isPending}
        onCancel={() => setAsking(false)}
        onConfirm={send}
      />
    </fieldset>
  )
}

function RatingList({ title, ratings, mine }: { title: string; ratings: GivenRating[]; mine: boolean }) {
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-h3">{title}</h3>
      <ul className="flex flex-col gap-2">
        {ratings.map((rating) => (
          <li
            key={`${rating.kind}-${rating.rateeId}`}
            className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm"
          >
            <span className="flex flex-wrap items-center gap-2">
              <span className="flex" aria-label={t('screen.rating.star', { n: rating.stars })} role="img">
                {[1, 2, 3, 4, 5].map((stars) => (
                  <Star
                    key={stars}
                    aria-hidden="true"
                    className={cn(
                      'size-4',
                      stars <= rating.stars ? 'fill-warning text-warning' : 'text-muted-foreground',
                    )}
                  />
                ))}
              </span>
              <span className="text-caption text-muted-foreground">
                {mine
                  ? t('screen.rating.forName', { name: rating.rateeName })
                  : t(rating.raterRole === 'CUSTOMER' ? 'screen.rating.byCustomer' : 'screen.rating.byMentor')}
              </span>
            </span>
            <span className="break-words whitespace-pre-line">{rating.comment}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** N-21's private feedback: stars and an optional comment, once, read only by Administrators. */
function PrivateFeedback({ jobId, ratings }: { jobId: string; ratings: Ratings }) {
  const ids = useId()
  const toasts = useToasts()
  const give = useGiveFeedback(jobId)
  const [stars, setStars] = useState(0)
  const [comment, setComment] = useState('')
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<string>()
  const send = () => {
    setError(undefined)
    const text = comment.trim()
    give.mutate(text === '' ? { stars } : { stars, comment: text }, {
      onSuccess: () => {
        setAsking(false)
        toasts.success(t('screen.rating.feedbackSent'))
      },
      onError: (failure) => {
        setAsking(false)
        setError(problemMessage(failure, { module: 'reputation' }).text)
      },
    })
  }
  if (ratings.feedbackGiven) {
    return <p className="text-body-sm">{t('screen.rating.feedbackGiven')}</p>
  }
  if (!ratings.feedbackDue) {
    return <p className="text-body-sm text-muted-foreground">{t('screen.rating.feedbackClosed')}</p>
  }
  return (
    <fieldset className="flex flex-col gap-2 rounded-md border border-border bg-background p-3">
      <legend className="px-1 text-label">{t('screen.rating.feedbackHeading')}</legend>
      <p className="text-body-sm">{t('screen.rating.feedbackNote')}</p>
      <span id={`${ids}-stars`} className="text-body-sm">
        {t('screen.rating.stars')}
      </span>
      <Stars value={stars} onChange={setStars} labelledBy={`${ids}-stars`} />
      <label htmlFor={`${ids}-comment`} className="text-label">
        {t('screen.rating.feedbackComment')}
      </label>
      <textarea
        id={`${ids}-comment`}
        value={comment}
        maxLength={2000}
        onChange={(event) => setComment(event.target.value)}
        className={cn(inputClass, 'min-h-18')}
      />
      <p className="text-caption text-muted-foreground">{t('screen.rating.final')}</p>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button type="button" disabled={stars === 0 || give.isPending} onClick={() => setAsking(true)}>
          {t('screen.rating.feedbackSubmit')}
        </Button>
      </div>
      <Confirm
        open={asking}
        title={t('screen.rating.feedbackConfirmTitle')}
        action={t('screen.rating.feedbackSubmit')}
        pending={give.isPending}
        onCancel={() => setAsking(false)}
        onConfirm={send}
      />
    </fieldset>
  )
}

function Confirm({
  open,
  title,
  action,
  pending,
  onCancel,
  onConfirm,
}: {
  open: boolean
  title: string
  action: string
  pending: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onCancel()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{title}</Dialog.Title>
          <Dialog.Description className="text-body-sm">{t('screen.rating.confirmBody')}</Dialog.Description>
          <div className="flex flex-wrap justify-end gap-3">
            <Button type="button" variant="secondary" onClick={onCancel}>
              {t('screen.rating.cancel')}
            </Button>
            <Button type="button" disabled={pending} onClick={onConfirm}>
              {action}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
