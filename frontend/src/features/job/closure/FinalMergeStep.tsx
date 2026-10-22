import { CircleCheck, Info } from 'lucide-react'
import { useId, useState } from 'react'
import { useSearchParams } from 'react-router'
import { TermHint } from '@/components/TermHint'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { redirectTo } from '@/lib/redirect'
import { OutsideLink } from '@/features/delivery/OutsideLink'
import { SensitiveChangeList } from '@/features/delivery/SensitiveChangeList'
import { useConfirmFinalMerge, useFinalMerge, useInstallLink, type FinalMerge } from './finalMergeApi'
import { DeclineToZip, ZipFallbackStep } from './ZipFallbackStep'

/** Where the GitHub return marks that nothing was installed (a request, or a blocked install). */
const NOT_SEEN = 'chua-thay'

/**
 * The Customer's step "Cho phép hợp nhất" in JOB-1's closure section (FR-37, AD-10, UX-DR30, UX-DR55):
 * the Term hint and "Cài ứng dụng hợp nhất trên GitHub ↗ (github.com)" with the FR-37 deadline; once
 * bound, "Đã cho phép cho kho {repo}". A refused selection, a request waiting for an organization owner
 * with the message to send them, a return that installed nothing, and a request no longer listed each
 * say what to do. The link is minted on click, with a single-use state, and the page goes to GitHub;
 * the return refetches. Once bound in Closing (Story 8.8) the code is checked, then the whole Job's
 * Sensitive change list, the workflow files already in the project, and N-18's checkbox lead to "Hợp
 * nhất vào dự án"; the merge ends with "Đã hợp nhất vào dự án của bạn" and its pull request on GitHub.
 * Nothing shows for anyone else, or once the authorization ended.
 */
export function FinalMergeStep({ jobId }: { jobId: string }) {
  const merge = useFinalMerge(jobId)
  if (!merge.data) {
    return null
  }
  if (merge.data.status === 'ENDED') {
    return <ZipFallbackStep jobId={jobId} merge={merge.data} />
  }
  return <Content jobId={jobId} merge={merge.data} />
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
    return <Merge jobId={jobId} merge={merge} />
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
      <div className="flex flex-wrap gap-3">
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
        <DeclineToZip jobId={jobId} repo={repo} />
      </div>
    </section>
  )
}

/** The Final Merge once the authorization is bound (FR-37, SG-19, UX-DR35, UX-DR55). */
function Merge({ jobId, merge }: { jobId: string; merge: FinalMerge }) {
  const ids = useId()
  const confirm = useConfirmFinalMerge(jobId)
  const [agreed, setAgreed] = useState(false)
  const [error, setError] = useState<string>()
  const repo = merge.repository
  const step = merge.merge
  const bound = (
    <p className="flex items-center gap-2 text-body-sm">
      <CircleCheck aria-hidden="true" className="size-4 shrink-0 text-success" />
      {t('screen.closure.finalMerge.bound', { repo })}
    </p>
  )
  if (step?.status === 'MERGED') {
    return (
      <div className="flex flex-col gap-2">
        <p className="flex items-center gap-2 text-body-sm font-semibold">
          <CircleCheck aria-hidden="true" className="size-4 shrink-0 text-success" />
          {t('screen.closure.finalMerge.merged')}
        </p>
        {step.pullUrl && (
          <OutsideLink url={step.pullUrl} domain="github.com" label={t('screen.closure.finalMerge.viewOnGitHub')} />
        )}
      </div>
    )
  }
  if (!step || step.status === 'CHECKING') {
    return (
      <div className="flex flex-col gap-2">
        {bound}
        {step && (
          <p role="status" className="text-body-sm text-muted-foreground">
            {t('screen.closure.finalMerge.checking')}
          </p>
        )}
        <DeclineToZip jobId={jobId} repo={repo} />
      </div>
    )
  }
  if (step.status === 'CONFIRMED') {
    return (
      <div className="flex flex-col gap-2">
        {bound}
        <p role="status" className="text-body-sm">
          {t('screen.closure.finalMerge.confirming')}
        </p>
      </div>
    )
  }
  if (step.status !== 'READY') {
    return bound
  }
  const run = () => {
    setError(undefined)
    confirm.mutate(undefined, {
      onError: (failure) => setError(problemMessage(failure, { module: 'repo' }).text),
    })
  }
  return (
    <section aria-labelledby={`${ids}-review`} className="flex flex-col gap-3 rounded-md border border-border p-4">
      {bound}
      <p id={`${ids}-review`} className="text-body-sm">
        {t('screen.closure.finalMerge.reviewLead')}
      </p>
      <SensitiveChangeList changes={step.sensitive} headingId={`${ids}-sensitive`} />
      {step.workflows.length > 0 && (
        <div role="note" className="flex flex-col gap-1 rounded-md border-l-4 border-primary bg-primary-soft p-3 text-body-sm">
          <p className="flex items-center gap-2 font-semibold">
            <Info aria-hidden="true" className="size-4 shrink-0 text-primary" />
            {t('screen.closure.finalMerge.workflowsTitle')}
          </p>
          <ul className="flex flex-col gap-0.5">
            {step.workflows.map((path) => (
              <li key={path}>
                <code className="font-mono text-caption break-all">{path}</code>
              </li>
            ))}
          </ul>
          <p>{t('screen.closure.finalMerge.workflowsLead')}</p>
        </div>
      )}
      <label className="flex items-start gap-2 text-body-sm">
        <input
          type="checkbox"
          className="mt-1 size-4"
          checked={agreed}
          onChange={(event) => setAgreed(event.target.checked)}
        />
        {t('screen.closure.finalMerge.agree')}
      </label>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap gap-3">
        <Button type="button" className="w-fit" disabled={!agreed || confirm.isPending} onClick={run}>
          {t('screen.closure.finalMerge.confirm')}
        </Button>
        <DeclineToZip jobId={jobId} repo={repo} />
      </div>
    </section>
  )
}
