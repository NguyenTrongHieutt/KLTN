import { CircleCheck } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { TermHint } from '@/components/TermHint'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { redirectTo } from '@/lib/redirect'
import { useFinalMerge, useInstallLink, type FinalMerge } from './finalMergeApi'

/** Where the GitHub return marks that nothing was installed (a request, or a blocked install). */
const NOT_SEEN = 'chua-thay'

/**
 * The Customer's step "Cho phép hợp nhất" in JOB-1's closure section (FR-37, AD-10, UX-DR30, UX-DR55):
 * the Term hint and "Cài ứng dụng hợp nhất trên GitHub ↗ (github.com)" with the FR-37 deadline; once
 * bound, "Đã cho phép cho kho {repo}". A refused selection, a request waiting for an organization owner
 * with the message to send them, a return that installed nothing, and a request no longer listed each
 * say what to do. The link is minted on click, with a single-use state, and the page goes to GitHub;
 * the return refetches. Nothing shows for anyone else, or once the authorization ended.
 */
export function FinalMergeStep({ jobId }: { jobId: string }) {
  const merge = useFinalMerge(jobId)
  return merge.data && merge.data.status !== 'ENDED' ? <Content jobId={jobId} merge={merge.data} /> : null
}

function Content({ jobId, merge }: { jobId: string; merge: FinalMerge }) {
  const toasts = useToasts()
  const link = useInstallLink(jobId)
  const [params] = useSearchParams()
  const [error, setError] = useState<string>()
  const repo = merge.repository
  const org = merge.requestOrg ?? merge.owner
  const date = merge.deadlineAt ? formatDayMonth(merge.deadlineAt) : t('screen.closure.finalMerge.beforeEnd')

  if (merge.status === 'BOUND' || merge.status === 'USED') {
    return (
      <p className="flex items-center gap-2 text-body-sm">
        <CircleCheck aria-hidden="true" className="size-4 shrink-0 text-success" />
        {t('screen.closure.finalMerge.bound', { repo })}
      </p>
    )
  }

  const install = () => {
    setError(undefined)
    link.mutate(undefined, {
      onSuccess: (next) => redirectTo(next.url),
      onError: (failure) => setError(problemMessage(failure, { module: 'repo' }).text),
    })
  }
  const notSeen = !merge.requestWaiting && !merge.requestRefused && params.get('hop-nhat') === NOT_SEEN
  const message = t('screen.closure.finalMerge.message', { repo, date, org })

  return (
    <section aria-labelledby="final-merge-step" className="flex flex-col gap-2 rounded-md border border-border p-4">
      <h3 id="final-merge-step" className="text-h3">
        <TermHint term="finalMergeAuthorization" />
      </h3>
      {merge.deadlineAt && <p className="text-body-sm">{t('screen.closure.finalMerge.deadline', { date })}</p>}
      {merge.refusal && (
        <p role="alert" className="text-body-sm text-destructive">
          {t('reason.repo.MERGE_APP_WRONG_REPOSITORY')}
        </p>
      )}
      {merge.requestWaiting && (
        <>
          <p className="w-fit rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold">
            {t('screen.closure.finalMerge.waitingBadge')}
          </p>
          <p className="text-body-sm">{t('screen.closure.finalMerge.waiting', { org, repo, date })}</p>
          <div className="flex flex-col gap-2 rounded-md bg-muted p-3">
            <p className="text-caption font-semibold">{t('screen.closure.finalMerge.messageTitle')}</p>
            <p className="text-body-sm break-words">{message}</p>
            <Button
              type="button"
              variant="secondary"
              size="compact"
              className="w-fit"
              onClick={() => {
                void navigator.clipboard
                  .writeText(message)
                  .then(() => toasts.success(t('screen.closure.finalMerge.copied')))
                  .catch(() => toasts.error(t('screen.closure.finalMerge.copyFailed')))
              }}
            >
              {t('screen.closure.finalMerge.copy')}
            </Button>
          </div>
        </>
      )}
      {merge.requestRefused && (
        <>
          <p className="w-fit rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold">
            {t('screen.closure.finalMerge.refusedBadge')}
          </p>
          <p className="text-body-sm">{t('screen.closure.finalMerge.refused', { org, repo, date })}</p>
        </>
      )}
      {notSeen && <p className="text-body-sm">{t('screen.closure.finalMerge.notSeen', { org, repo, date })}</p>}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {!merge.requestWaiting && (
        <Button type="button" className="w-fit" disabled={link.isPending} onClick={install}>
          {t(
            merge.requestRefused
              ? 'screen.closure.finalMerge.resend'
              : notSeen || merge.refusal
                ? 'screen.closure.finalMerge.reinstall'
                : 'screen.closure.finalMerge.install',
          )}
        </Button>
      )}
    </section>
  )
}
