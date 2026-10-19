import { CircleCheck, ExternalLink, Info, LoaderCircle, TriangleAlert } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TermHint } from '@/components/TermHint'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDate, formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  scanActive,
  useFindings,
  useProduct,
  useRescan,
  useScanHistory,
  type Finding,
  type Product,
  type Scan,
} from './productApi'
import { DeleteProduct } from './DeleteProduct'
import { domainOf } from './productText'
import { ScanStateBadge } from './ScanStateBadge'

/**
 * CUS-4 (`/san-pham/{productId}`, FR-7, FR-8, FR-65): the Product, its scan, its Findings with their
 * states, the scan history, its Source Repository, and its information. While the scan waits or runs
 * the page polls every 5 s under the Busy banner (UX-DR21); a scan that did not finish says vibefix
 * tries again; "Kiểm tra lại" runs both scans again, 5 times a day. A completed scan shows the result of
 * the Secret Scan and the Personal-Data Check. Each Finding shows its type, file, commit, date, and
 * masked values only. "Xóa sản phẩm" deletes it after a destructive Confirmation dialog; Jobs
 * arrive with Epic 3.
 */
export function ProductPage() {
  const { productId } = useParams()
  const product = useProduct(productId)
  usePageTitle(product.data?.name ?? t('screen.product.title'))
  if (product.isError && isNotFound(product.error)) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[880px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={product} skeleton={<Skeleton className="h-64 w-full" />}>
        {product.data && <ProductDetail product={product.data} />}
      </RegionState>
    </div>
  )
}

function ProductDetail({ product }: { product: Product }) {
  const scan = product.scan
  return (
    <>
      <nav aria-label={t('screen.product.detail.breadcrumb')} className="text-caption text-muted-foreground">
        <Link to="/san-pham" className="text-primary underline-offset-4 hover:underline">
          {t('screen.product.detail.breadcrumb')}
        </Link>{' '}
        / <span className="break-words">{product.name}</span>
      </nav>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <PageHeading className="break-words">{product.name}</PageHeading>
          <ScanStateBadge product={product} />
        </div>
        <div className="flex flex-wrap items-start gap-3">
          <a
            href={product.repositoryUrl}
            target="_blank"
            rel="noopener noreferrer"
            className={cn(buttonVariants({ variant: 'secondary' }), 'gap-1.5')}
          >
            <ExternalLink aria-hidden="true" className="size-4" />
            {t('screen.product.detail.openSource')}
            <span className="text-caption text-muted-foreground">{t('screen.product.domain', { domain: 'github.com' })}</span>
            <span className="sr-only">{t('screen.product.newTab')}</span>
          </a>
          {scan && <RescanButton product={product} scan={scan} />}
          <PostJobLink product={product} />
        </div>
      </div>
      {scan ? <ScanStatus scan={scan} /> : <AwaitingScan />}
      <p className="text-body-sm break-words">{product.description}</p>
      {scan && <FindingsSection product={product} scan={scan} />}
      {scan && scan.unscannedCount > 0 && <UnscannedFiles scan={scan} />}
      {scan && <ScanHistory product={product} />}
      <section aria-labelledby="product-info-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
        <h2 id="product-info-heading" className="text-h3">
          {t('screen.product.detail.info')}
        </h2>
        <dl className="grid gap-x-4 gap-y-2 text-body-sm sm:grid-cols-[max-content_1fr]">
          <dt className="text-muted-foreground">{t('screen.product.detail.madeWith')}</dt>
          <dd>{product.tools.map((tool) => t(`screen.product.tools.${tool}` as MessageKey)).join(', ')}</dd>
          <dt className="text-muted-foreground">{t('screen.product.detail.demo')}</dt>
          <dd className="break-all">
            {product.demoUrl ? (
              <a href={product.demoUrl} target="_blank" rel="noopener noreferrer nofollow" className="text-primary underline-offset-4 hover:underline">
                {domainOf(product.demoUrl)}
                <span className="sr-only">{t('screen.product.newTab')}</span>
              </a>
            ) : (
              t('screen.product.detail.noDemo')
            )}
          </dd>
          {product.forkParent && (
            <>
              <dt className="text-muted-foreground">{t('screen.product.detail.forkOf')}</dt>
              <dd className="break-all">{product.forkParent}</dd>
            </>
          )}
          <dt className="text-muted-foreground">{t('screen.product.detail.registered')}</dt>
          <dd>{formatDate(product.createdAt)}</dd>
        </dl>
      </section>
      <DeleteProduct product={product} />
    </>
  )
}

/** A Product registered before its scan was requested still says it waits for one. */
function AwaitingScan() {
  return (
    <div role="status" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4">
      <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
      <div className="flex flex-col gap-0.5">
        <p className="text-label font-semibold">{t('screen.product.detail.awaitingTitle')}</p>
        <p className="text-body-sm">{t('screen.product.detail.awaitingBody')}</p>
      </div>
    </div>
  )
}

/** The Busy banner while the scan waits or runs; the warning once an attempt failed (UX-DR21, UX-DR27). */
function ScanStatus({ scan }: { scan: Scan }) {
  if (scan.status === 'PENDING' || scan.status === 'RUNNING') {
    return (
      <div role="status" aria-live="polite" className="flex items-center gap-3 rounded-md bg-info-soft p-4">
        <LoaderCircle aria-hidden="true" className="size-5 shrink-0 animate-spin text-primary motion-reduce:animate-none" />
        <p className="text-body-sm">{t('screen.product.detail.scanBusy')}</p>
      </div>
    )
  }
  if (scan.status === 'RETRYING' || scan.status === 'FAILED') {
    return (
      <div role="status" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
        <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
        <div className="flex flex-col gap-0.5">
          <p className="text-label font-semibold">{t('screen.product.detail.scanNotFinished')}</p>
          <p className="text-body-sm">{t('screen.product.detail.scanNotFinishedBody')}</p>
        </div>
      </div>
    )
  }
  return null
}

/** CUS-6's entry: open once the intake scan has finished (FR-9; Code Job Types wait for the Findings). */
function PostJobLink({ product }: { product: Product }) {
  const ready = product.scan?.status === 'SUCCEEDED' || (product.scan !== undefined && product.openFindings > 0)
  if (!ready) {
    return (
      <div className="flex flex-col gap-1">
        <Button type="button" disabled aria-describedby="post-job-wait">
          {t('screen.product.detail.postJob')}
        </Button>
        <p id="post-job-wait" className="max-w-[220px] text-caption text-muted-foreground">
          {t('screen.product.detail.postJobWait')}
        </p>
      </div>
    )
  }
  return (
    <Link to={`/san-pham/${product.id}/cong-viec-moi`} className={buttonVariants()}>
      {t('screen.product.detail.postJob')}
    </Link>
  )
}

/** "Kiểm tra lại": disabled while a scan waits or runs; the day's limit reads when it can be used again. */
function RescanButton({ product, scan }: { product: Product; scan: Scan }) {
  const rescan = useRescan(product.id)
  const toasts = useToasts()
  const busy = scanActive(scan)
  const start = () =>
    rescan.mutate(undefined, {
      onSuccess: () => toasts.success(t('screen.product.detail.rescanStarted')),
      onError: (error) => toasts.error(rescanRefusal(error)),
    })
  return (
    <div className="flex flex-col gap-1">
      <Button
        type="button"
        variant="secondary"
        onClick={start}
        disabled={busy || rescan.isPending}
        aria-describedby={busy ? 'rescan-busy' : undefined}
      >
        {t('screen.product.detail.rescan')}
      </Button>
      {busy && (
        <p id="rescan-busy" className="max-w-[220px] text-caption text-muted-foreground">
          {t('screen.product.detail.rescanBusy')}
        </p>
      )}
    </div>
  )
}

function rescanRefusal(error: unknown): string {
  if (error instanceof ApiError && error.problem.reason === 'SCAN_LIMIT') {
    const at = new Date(Date.now() + (error.retryAfter ?? 0) * 1000).toISOString()
    return t('reason.product.SCAN_LIMIT', { time: formatDayMonthTime(at) })
  }
  return problemMessage(error, { module: 'product' }).text
}

function FindingsSection({ product, scan }: { product: Product; scan: Scan }) {
  const findings = useFindings(product)
  const all = findings.data ?? []
  return (
    <section aria-labelledby="findings-heading" className="flex flex-col gap-3">
      <h2 id="findings-heading" className="text-h2">
        {t('screen.product.detail.findingsHeading')}
      </h2>
      {scan.status === 'SUCCEEDED' && (
        <CheckSummary findings={findings.data?.filter((finding) => finding.status === 'OPEN')} />
      )}
      {product.openFindings > 0 && (
        <div role="alert" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
          <div className="flex flex-col gap-0.5">
            <p className="text-label font-semibold">{t('screen.product.detail.findingsOpen', { n: product.openFindings })}</p>
            <p className="text-body-sm">{t('screen.product.detail.findingsOpenBody')}</p>
          </div>
        </div>
      )}
      {product.openFindings === 0 && scan.status === 'SUCCEEDED' && (
        <div className="flex items-center gap-3 rounded-lg border border-border bg-card p-4">
          <CircleCheck aria-hidden="true" className="size-5 shrink-0 text-success" />
          <p className="text-body-sm">{t('screen.product.detail.clean')}</p>
        </div>
      )}
      {product.openFindings === 0 && scan.status !== 'SUCCEEDED' && all.length === 0 && (
        <p className="text-body-sm text-muted-foreground">{t('screen.product.detail.findingsPending')}</p>
      )}
      {(product.openFindings > 0 || all.length > 0) && (
        <RegionState query={findings} skeleton={<Skeleton className="h-32 w-full" />}>
          <ul aria-label={t('screen.product.detail.findingsList')} className="flex flex-col gap-3">
            {all.map((finding) => (
              <FindingCard key={finding.id} productId={product.id} finding={finding} />
            ))}
          </ul>
        </RegionState>
      )}
      <p className="text-caption text-muted-foreground">{t('screen.product.detail.limits')}</p>
    </section>
  )
}

/** The Secret Scan and the Personal-Data Check, each clean or with its open Findings counted. */
function CheckSummary({ findings }: { findings: Finding[] | undefined }) {
  if (findings === undefined) {
    return null
  }
  const secrets = findings.filter((finding) => finding.category === 'CREDENTIAL').length
  const personal = findings.filter((finding) => finding.category === 'PERSONAL_DATA').length
  return (
    <section aria-label={t('screen.product.detail.checksLabel')} className="rounded-lg border border-border bg-card p-4">
      <dl className="grid gap-2 text-body-sm sm:grid-cols-[max-content_1fr]">
        <dt className="font-semibold">
          <TermHint term="secretScan" />
        </dt>
        <dd className={secrets > 0 ? 'text-destructive' : 'text-success'}>
          {secrets > 0 ? t('screen.product.detail.checkIssues', { n: secrets }) : t('screen.product.detail.checkSecretsClean')}
        </dd>
        <dt className="font-semibold">
          <TermHint term="personalDataCheck" />
        </dt>
        <dd className={personal > 0 ? 'text-destructive' : 'text-success'}>
          {personal > 0 ? t('screen.product.detail.checkIssues', { n: personal }) : t('screen.product.detail.checkPersonalClean')}
        </dd>
      </dl>
    </section>
  )
}

/**
 * One Finding (UX-DR31): the plain type, where it was found, and the masked value, with no reveal or
 * copy; for personal data (FR-65), the lines holding it and two masked samples.
 */
function FindingCard({ productId, finding }: { productId: string; finding: Finding }) {
  const personal = finding.category === 'PERSONAL_DATA'
  const open = finding.status === 'OPEN'
  const badge =
    finding.status === 'OPEN'
      ? { text: t('screen.product.detail.findingOpen'), tone: 'bg-destructive-soft text-destructive' }
      : finding.status === 'RESOLVED'
        ? {
            text: t('screen.product.detail.findingResolved', { date: formatDayMonth(finding.resolvedAt ?? finding.createdAt) }),
            tone: 'bg-success-soft text-success',
          }
        : {
            text: t(personal ? 'screen.product.detail.findingFalsePersonal' : 'screen.product.detail.findingFalseCredential'),
            tone: 'bg-muted text-foreground',
          }
  return (
    <li
      className={cn(
        'flex flex-col gap-2 rounded-lg border border-l-4 border-border bg-card p-4',
        open ? 'border-l-destructive' : finding.status === 'RESOLVED' ? 'border-l-success' : 'border-l-border',
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-h3 break-words">{finding.type}</h3>
        <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', badge.tone)}>{badge.text}</span>
      </div>
      <p className="text-body-sm text-muted-foreground">
        {t('screen.product.detail.findingFile')} <span className="font-mono break-all text-foreground">{finding.path}</span> ·{' '}
        {t('screen.product.detail.findingCommit')} <span className="font-mono text-foreground">{finding.commit.slice(0, 7)}</span>{' '}
        · {formatDate(finding.committedAt)}
        {personal && finding.matchCount !== undefined && <> · {t('screen.product.detail.findingLines', { n: finding.matchCount })}</>}
      </p>
      <p className="text-body-sm">
        <span className="text-muted-foreground">
          {t(personal ? 'screen.product.detail.findingSamples' : 'screen.product.detail.findingMasked')}
        </span>{' '}
        <span className="font-mono break-all select-none">{finding.maskedPreview}</span>
      </p>
      <Link
        to={`/san-pham/${productId}/van-de/${finding.id}`}
        className="w-fit text-label font-semibold text-primary underline-offset-4 hover:underline"
      >
        {t(open ? 'screen.product.detail.findingHowTo' : 'screen.product.detail.findingDetails')}
      </Link>
    </li>
  )
}

/** CUS-4 "Lịch sử kiểm tra": each run's time, checks, result, and why it ran. */
function ScanHistory({ product }: { product: Product }) {
  const history = useScanHistory(product)
  return (
    <section aria-labelledby="history-heading" className="flex flex-col gap-3">
      <h2 id="history-heading" className="text-h2">
        {t('screen.product.detail.historyHeading')}
      </h2>
      <RegionState query={history} skeleton={<Skeleton className="h-24 w-full" />}>
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full text-left text-body-sm">
            <thead className="border-b border-border text-caption text-muted-foreground">
              <tr>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.product.detail.historyDate')}
                </th>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.product.detail.historyChecks')}
                </th>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.product.detail.historyResult')}
                </th>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.product.detail.historyNote')}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {(history.data ?? []).map((run) => (
                <tr key={run.id}>
                  <td className="px-4 py-2 whitespace-nowrap">{formatDayMonthTime(run.requestedAt)}</td>
                  <td className="px-4 py-2">{t('screen.product.detail.historyBoth')}</td>
                  <td className="px-4 py-2">
                    <RunResult run={run} />
                  </td>
                  <td className="px-4 py-2 text-muted-foreground">
                    {t(`screen.product.detail.purpose${run.purpose}` as MessageKey)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </RegionState>
    </section>
  )
}

function RunResult({ run }: { run: Scan }) {
  const [text, tone] =
    run.status === 'PENDING' || run.status === 'RUNNING'
      ? [t('screen.product.detail.resultRunning'), 'bg-primary-soft text-primary']
      : run.status === 'SUCCEEDED'
        ? (run.found ?? 0) > 0
          ? [t('screen.product.detail.resultFound', { n: run.found ?? 0 }), 'bg-destructive-soft text-destructive']
          : [t('screen.product.detail.resultClean'), 'bg-success-soft text-success']
        : [t('screen.product.detail.resultNotFinished'), 'bg-warning-soft text-warning']
  return <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold whitespace-nowrap', tone)}>{text}</span>
}

function UnscannedFiles({ scan }: { scan: Scan }) {
  const more = scan.unscannedCount - scan.unscanned.length
  return (
    <section aria-labelledby="unscanned-heading" className="flex flex-col gap-3">
      <h2 id="unscanned-heading" className="text-h2">
        {t('screen.product.detail.unscannedHeading')}
      </h2>
      <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card text-body-sm">
        {scan.unscanned.map((file) => (
          <li key={file.path} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
            <span className="font-mono break-all">{file.path}</span>
            <span className="text-caption text-muted-foreground">
              {t(file.reason === 'NOT_EXTRACTED' ? 'screen.product.detail.unscannedNotExtracted' : 'screen.product.detail.unscannedSize', {
                size: formatSize(file.sizeBytes),
              })}
            </span>
          </li>
        ))}
      </ul>
      {more > 0 && <p className="text-caption text-muted-foreground">{t('screen.product.detail.unscannedMore', { n: more })}</p>}
      <p className="text-caption text-muted-foreground">{t('screen.product.detail.unscannedHint')}</p>
    </section>
  )
}
