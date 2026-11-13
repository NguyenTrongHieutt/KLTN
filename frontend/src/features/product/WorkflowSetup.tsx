import { Copy, Download, ExternalLink } from 'lucide-react'
import type { ReactNode } from 'react'
import { TermHint } from '@/components/TermHint'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { rich } from '@/i18n/rich'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'
import type { SourceStatus, WorkflowFile } from './productApi'
import {
  SECRET_PROVIDERS,
  domainOf,
  editWorkflowUrl,
  newWorkflowUrl,
  statusTone,
  workflowActionsUrl,
} from './productText'

/** An outside link: a new tab without the opener, with its domain beside it (AD-35). */
export function OutLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex w-fit flex-wrap items-center gap-1 text-body-sm text-primary underline-offset-4 hover:underline"
    >
      {children}
      <ExternalLink aria-hidden="true" className="size-3.5" />
      <span className="text-caption text-muted-foreground">{t('screen.product.domain', { domain: domainOf(href) })}</span>
      <span className="sr-only">{t('screen.product.newTab')}</span>
    </a>
  )
}

/** A GitHub action the Customer takes there, as a button that opens GitHub in a new tab ("… ↗ (github.com)"). */
export function GitHubAction({
  href,
  variant = 'primary',
  children,
}: {
  href: string
  variant?: 'primary' | 'secondary'
  children: ReactNode
}) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className={cn(buttonVariants(variant === 'secondary' ? { variant: 'secondary' } : {}), 'w-fit gap-1.5')}
    >
      <ExternalLink aria-hidden="true" className="size-4" />
      {children}
      <span className="text-caption">{t('screen.product.domain', { domain: 'github.com' })}</span>
      <span className="sr-only">{t('screen.product.newTab')}</span>
    </a>
  )
}

/**
 * N-2's guidance (FR-8, D8): what to check by hand, each credential's page, and the optional gitleaks run on the
 * Source Repository's Actions tab, which never gates. CUS-3 step 3 shows it, and the SHA confirmation card in a Sheet.
 * @param htmlUrl the Source Repository's page on GitHub
 */
export function SecretGuidance({ htmlUrl }: { htmlUrl: string }) {
  return (
    <>
      <p className="text-body-sm">{t('notice.product.guidance')}</p>
      <p className="text-label">{t('screen.product.wizard.secretsItemsHeading')}</p>
      <ul className="flex flex-col gap-1.5">
        {SECRET_PROVIDERS.map((item) => (
          <li key={item.key} className="flex flex-wrap items-center gap-x-2 text-body-sm">
            <span>{t(`screen.product.wizard.secret.${item.key}` as MessageKey)}</span>
            <OutLink href={item.url}>{t('screen.product.wizard.secretLink', { provider: item.provider })}</OutLink>
          </li>
        ))}
      </ul>
      <p className="text-label">
        {rich('screen.product.wizard.selfScanTerm', { term: <TermHint term="tuKiemTraKhoaBiMat" /> })}
      </p>
      <OutLink href={workflowActionsUrl(htmlUrl)}>{t('screen.product.wizard.selfScan')}</OutLink>
      <p className="text-caption text-muted-foreground">{t('screen.product.wizard.selfScanHint')}</p>
    </>
  )
}

/** "Sao chép nội dung": the published `vibefix.yml` to the clipboard. */
function CopyWorkflowButton({ content, lead = false }: { content: string; lead?: boolean }) {
  const toasts = useToasts()
  return (
    <Button
      type="button"
      variant={lead ? 'primary' : 'secondary'}
      size={lead ? 'default' : 'compact'}
      className="w-fit gap-1.5"
      onClick={() => {
        void navigator.clipboard
          .writeText(content)
          .then(() => toasts.success(t('screen.product.wizard.contentCopied')))
          .catch(() => toasts.error(t('screen.product.wizard.copyFailed')))
      }}
    >
      <Copy aria-hidden="true" className="size-4" />
      {t('screen.product.wizard.copyContent')}
    </Button>
  )
}

/**
 * For when GitHub's prefilled page does not open: the published `vibefix.yml` to copy or download, and where it goes
 * (the Workflow setup card's fallback, CUS-3 step 3 and CUS-4).
 * @param copy whether the fallback has its own copy button; none when the actions already lead with one
 */
export function WorkflowFallback({
  file,
  branch,
  repo,
  copy = true,
}: {
  file: WorkflowFile
  branch: string
  repo: string
  copy?: boolean
}) {
  return (
    <div className="flex flex-col gap-2 rounded-md bg-muted p-3">
      <p className="text-caption font-semibold">{t('screen.product.wizard.fallbackHeading')}</p>
      <p className="text-body-sm break-words">{t('screen.product.wizard.fallbackBody', { path: file.path, branch, repo })}</p>
      <div className="flex flex-wrap gap-2">
        {copy && <CopyWorkflowButton content={file.content} />}
        <a
          href={`data:text/yaml;charset=utf-8,${encodeURIComponent(file.content)}`}
          download="vibefix.yml"
          className={cn(buttonVariants({ variant: 'secondary', size: 'compact' }), 'gap-1.5')}
        >
          <Download aria-hidden="true" className="size-4" />
          {t('screen.product.wizard.download')}
        </a>
      </div>
    </div>
  )
}

/**
 * The Workflow setup card's way to put the published `vibefix.yml` on GitHub (AD-45, Story 23.5c). With no file there,
 * "Thêm file workflow ↗" opens GitHub's new-file page prefilled with it. With a file there already (a different one,
 * or a version nearing its end date), the new-file page would refuse the path and the edit page cannot be prefilled,
 * so the actions lead with "Sao chép nội dung", then "Sửa file workflow ↗" on GitHub's edit page.
 * @param recheck the card's check is "Kiểm tra lại" (CUS-4), not "Kiểm tra" (CUS-3)
 * @param hints the hints of a surface whose way back is another action, such as the step tracker's "Thử lại"
 */
export function WorkflowFileActions({
  file,
  htmlUrl,
  branch,
  repo,
  existing,
  recheck,
  hints,
}: {
  file: WorkflowFile
  htmlUrl: string
  branch: string
  repo: string
  existing: boolean
  recheck: boolean
  hints?: { add: MessageKey; edit: MessageKey }
}) {
  const editHint = hints?.edit ?? (recheck ? 'screen.product.setup.editWorkflowHint' : 'screen.product.wizard.editWorkflowHint')
  const addHint = hints?.add ?? (recheck ? 'screen.product.setup.addWorkflowHint' : 'screen.product.wizard.addWorkflowHint')
  return (
    <div className="flex flex-col gap-2">
      {existing ? (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <CopyWorkflowButton content={file.content} lead />
            <GitHubAction href={editWorkflowUrl(htmlUrl, branch, file.path)} variant="secondary">
              {t('screen.product.wizard.editWorkflow')}
            </GitHubAction>
          </div>
          <p className="text-caption text-muted-foreground">{t(editHint, { path: file.path })}</p>
        </>
      ) : (
        <>
          <GitHubAction href={newWorkflowUrl(htmlUrl, branch, file.path, file.content)}>
            {t('screen.product.wizard.addWorkflow')}
          </GitHubAction>
          <p className="text-caption text-muted-foreground">{t(addHint, { path: file.path })}</p>
        </>
      )}
      <WorkflowFallback file={file} branch={branch} repo={repo} copy={!existing} />
    </div>
  )
}

/**
 * The Workflow status badge (CUS-2, CUS-4, Story 23.5c): "Sẵn sàng", or what the Customer must fix on GitHub, in the
 * status's one tone. It shows the recorded status, so no GitHub call is made to render it.
 */
export function SourceStatusBadge({ status, className }: { status: SourceStatus; className?: string }) {
  return (
    <span
      className={cn(
        'w-fit rounded-full px-2 py-0.5 text-caption font-semibold',
        statusTone(status) === 'success' ? 'bg-success-soft text-success' : 'bg-warning-soft text-warning',
        className,
      )}
    >
      <span className="sr-only">{t('screen.product.status.prefix')}</span>
      {t(`screen.product.status.${status}` as MessageKey)}
    </span>
  )
}
