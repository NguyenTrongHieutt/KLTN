import { CircleCheck, Info, TriangleAlert } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatCalendarDate, formatDayMonthTime } from '@/lib/dates'
import { useSourceCheck, useWorkflowFile, type Product, type SourceCheck } from './productApi'
import { checkProblem, workflowActionsUrl } from './productText'
import { GitHubAction, OutLink, SourceStatusBadge, WorkflowFileActions } from './WorkflowSetup'

/**
 * CUS-4's compact Workflow setup card (UX-DR113, AD-45, Story 23.5c): the recorded Source App and workflow status as
 * its badge with the last check time, "Kiểm tra lại", and the way to fix what failed in EXPERIENCE.md's words: the
 * App's install page for a missing Source App, the installation's settings for a wrong selection, "Thêm file workflow
 * ↗" for a missing file, and the copy with "Sửa file workflow ↗" for a file already there. A version nearing its end
 * date shows its alert (R12), and the optional scan on the repository's Actions tab sits below. A Product no check has
 * read yet says so instead of claiming it is ready. The page refetches on window focus after a GitHub round trip and on
 * "Kiểm tra lại"; nothing polls.
 */
export function SourceSetupCard({ product }: { product: Product }) {
  const check = useSourceCheck(product.id)
  const [last, setLast] = useState<SourceCheck>()
  const [refusal, setRefusal] = useState<string>()
  // A "Kiểm tra lại" answer counts only for the check the Product records now: a later check, any check, replaces it.
  const fresh = last && last.checkedAt === product.sourceCheckedAt && last.status === product.sourceStatus ? last : undefined

  function run() {
    setRefusal(undefined)
    check.mutate(undefined, {
      onSuccess: (result) => setLast(result),
      onError: (error) => setRefusal(checkProblem(error)),
    })
  }

  const endsOn = fresh ? fresh.workflowEndsOn : product.workflowEndsOn
  return (
    <section aria-labelledby="source-setup-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="source-setup-heading" className="text-h3">
          {t('screen.product.setup.heading')}
        </h2>
        <SourceStatusBadge status={product.sourceStatus} />
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="secondary" disabled={check.isPending} onClick={run}>
          {check.isPending ? t('screen.product.setup.rechecking') : t('screen.product.setup.recheck')}
        </Button>
        <span className="text-caption text-muted-foreground">
          {product.sourceCheckedAt
            ? t('screen.product.setup.checkedAt', { time: formatDayMonthTime(product.sourceCheckedAt) })
            : t('screen.product.setup.neverChecked')}
        </span>
      </div>
      {refusal && (
        <p role="alert" className="text-body-sm text-destructive">
          {refusal}
        </p>
      )}
      <StatusDetail product={product} fresh={fresh} />
      {endsOn && product.sourceStatus === 'READY' && (
        <Problem>
          <p className="text-body-sm">{t('screen.product.setup.ending', { date: formatCalendarDate(endsOn) })}</p>
          <WorkflowActions product={product} existing />
        </Problem>
      )}
      <div className="flex flex-col gap-1">
        <OutLink href={workflowActionsUrl(product.repositoryUrl)}>{t('screen.product.wizard.selfScan')}</OutLink>
        <p className="text-caption text-muted-foreground">{t('screen.product.setup.selfScanHint')}</p>
      </div>
    </section>
  )
}

/** What the recorded status means, in its EXPERIENCE.md copy, with the way to fix it on GitHub. */
function StatusDetail({ product, fresh }: { product: Product; fresh?: SourceCheck }) {
  const repo = product.repositoryFullName
  switch (product.sourceStatus) {
    case 'READY':
      if (!product.sourceCheckedAt) {
        // Registered before the status was recorded: nothing is known yet, so nothing is claimed.
        return (
          <p className="flex items-center gap-2 text-body-sm text-muted-foreground">
            <Info aria-hidden="true" className="size-4 shrink-0" />
            {t('screen.product.setup.notChecked', { repo })}
          </p>
        )
      }
      return (
        <p role="status" className="flex items-center gap-2 text-body-sm text-success">
          <CircleCheck aria-hidden="true" className="size-4 shrink-0" />
          {t('screen.product.setup.ready', { repo })}
        </p>
      )
    case 'SOURCE_APP_MISSING': {
      const install = fresh?.installUrl ?? product.installUrl
      return (
        <Problem>
          <p className="text-body-sm break-words">{t('screen.product.wizard.appMissing', { repo })}</p>
          {install && (
            <>
              <GitHubAction href={install}>{t('screen.product.wizard.install')}</GitHubAction>
              <p className="text-caption text-muted-foreground">{t('screen.product.setup.installReturn')}</p>
            </>
          )}
        </Problem>
      )
    }
    case 'SOURCE_APP_WRONG_REPOSITORY': {
      const settings = fresh?.settingsUrl ?? product.sourceApp?.settingsUrl
      return (
        <Problem>
          <p className="text-body-sm break-words">{t('screen.product.setup.wrongRepository', { repo })}</p>
          {settings && (
            <>
              <GitHubAction href={settings}>{t('screen.product.wizard.reselect')}</GitHubAction>
              <p className="text-caption text-muted-foreground">{t('screen.product.setup.reselectHint')}</p>
            </>
          )}
        </Problem>
      )
    }
    case 'WORKFLOW_MISSING':
    case 'WORKFLOW_MISMATCH':
      return (
        <Problem>
          <p className="text-label font-semibold break-words">
            {product.sourceStatus === 'WORKFLOW_MISSING'
              ? t('screen.product.wizard.workflowMissing', { repo })
              : t('screen.product.wizard.workflowMismatch')}
          </p>
          {fresh?.lineEndings && (
            <p className="flex gap-2 text-body-sm">
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              {t('screen.product.wizard.workflowLineEndings')}
            </p>
          )}
          <WorkflowActions product={product} existing={product.sourceStatus === 'WORKFLOW_MISMATCH'} />
        </Problem>
      )
  }
}

/** The published file onto GitHub: the new-file page for a missing file, the copy and the edit page for one there. */
function WorkflowActions({ product, existing }: { product: Product; existing: boolean }) {
  const file = useWorkflowFile()
  return (
    <RegionState query={file} skeleton={<Skeleton className="h-24 w-full" />}>
      {file.data && (
        <WorkflowFileActions
          file={file.data}
          htmlUrl={product.repositoryUrl}
          branch={product.defaultBranch}
          repo={product.repositoryFullName}
          existing={existing}
          recheck
        />
      )}
    </RegionState>
  )
}

/**
 * An Alert that describes a current condition, so it is not dismissible (EXPERIENCE.md Alert), in the warning tone
 * CUS-2's badge gives every failing Workflow status ({@link statusTone}); the end-date alert shares it.
 */
function Problem({ children }: { children: ReactNode }) {
  return (
    <div role="alert" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <div className="flex min-w-0 grow flex-col gap-2">{children}</div>
    </div>
  )
}
