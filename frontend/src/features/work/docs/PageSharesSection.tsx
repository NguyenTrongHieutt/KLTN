import { useId, useState } from 'react'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { usePageShares, useSavePageShares, type PageShares } from './sharedPagesApi'

/**
 * "Chia sẻ tài liệu cũ" on the Customer's JOB-6 (FR-70, UX-DR59): the Product's other Jobs with customer-visible
 * Pages, Assessment Reports first, none checked until the Customer checks it. "Lưu chia sẻ" shares exactly the
 * checked Jobs with the Mentor and the active Crew, read-only, until this Job ends. Hidden when there is nothing
 * to offer; read-only once the Job's state no longer allows changes.
 */
export function PageSharesSection({ jobId }: { jobId: string }) {
  const shares = usePageShares(jobId)
  if (!shares.data || shares.data.jobs.length === 0 || (!shares.data.editable && !shares.data.jobs.some((job) => job.shared))) {
    return null
  }
  return <Checklist key={shares.data.jobs.map((job) => `${job.jobId}:${job.shared}`).join()} jobId={jobId} shares={shares.data} />
}

function Checklist({ jobId, shares }: { jobId: string; shares: PageShares }) {
  const ids = useId()
  const toasts = useToasts()
  const save = useSavePageShares(jobId)
  const [chosen, setChosen] = useState(() => new Set(shares.jobs.filter((job) => job.shared).map((job) => job.jobId)))
  const [error, setError] = useState<string>()
  const changed = shares.jobs.some((job) => job.shared !== chosen.has(job.jobId))
  const shown = shares.editable ? shares.jobs : shares.jobs.filter((job) => job.shared)

  function toggle(jobId: string, on: boolean) {
    const next = new Set(chosen)
    if (on) {
      next.add(jobId)
    } else {
      next.delete(jobId)
    }
    setChosen(next)
  }

  return (
    <section
      aria-labelledby={`${ids}-heading`}
      className="flex w-full max-w-[820px] flex-col gap-3 self-center rounded-lg border border-border bg-card p-5"
    >
      <h2 id={`${ids}-heading`} className="text-h2">
        {t('screen.pageShares.heading')}
      </h2>
      <p className="text-body-sm text-muted-foreground">
        {t(shares.editable ? 'screen.pageShares.body' : 'screen.pageShares.closed')}
      </p>
      <ul className="flex flex-col divide-y divide-border">
        {shown.map((job) => (
          <li key={job.jobId} className="py-2">
            <label className="flex min-h-tap-min items-start gap-3 text-body-sm">
              <input
                type="checkbox"
                checked={chosen.has(job.jobId)}
                disabled={!shares.editable || save.isPending}
                onChange={(event) => toggle(job.jobId, event.target.checked)}
                className="mt-1 size-4"
              />
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold break-words">{job.title}</span>
                  {job.assessmentReport && (
                    <span className="rounded-full bg-primary-soft px-2 py-0.5 text-caption font-semibold text-primary">
                      {t('screen.pageShares.report')}
                    </span>
                  )}
                </span>
                <span className="text-caption text-muted-foreground">{t('screen.pageShares.pages', { n: job.pages })}</span>
              </span>
            </label>
          </li>
        ))}
      </ul>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {shares.editable && (
        <div className="flex justify-end">
          <Button
            type="button"
            disabled={!changed || save.isPending}
            onClick={() =>
              save.mutate([...chosen], {
                onSuccess: () => {
                  setError(undefined)
                  toasts.success(t('screen.pageShares.saved'))
                },
                onError: (failure) => setError(problemMessage(failure, { module: 'work' }).text),
              })
            }
          >
            {t('screen.pageShares.save')}
          </Button>
        </div>
      )}
    </section>
  )
}
