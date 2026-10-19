import { CircleCheck, ExternalLink, Info, LoaderCircle, TriangleAlert } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useFindings, useProduct, type Finding, type Product, type Scan } from './productApi'
import { domainOf } from './productText'
import { ScanStateBadge } from './ScanStateBadge'

/**
 * CUS-4 (`/san-pham/{productId}`, FR-7, FR-8): the Product, its scan, its open Findings, its Source
 * Repository, and its information. While the scan waits or runs the page polls every 5 s under the
 * Busy banner (UX-DR21); a scan that did not finish says vibefix tries again. Each Finding shows its
 * type, file, commit, date, and masked value only. The scan history, re-scan, and resolving a
 * Finding (Stories 2.5 to 2.7), Jobs (Epic 3), and deletion (Story 2.8) come with their stories.
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
      </div>
      {scan ? <ScanStatus scan={scan} /> : <AwaitingScan />}
      <p className="text-body-sm break-words">{product.description}</p>
      {scan && <FindingsSection product={product} scan={scan} />}
      {scan && scan.unscannedCount > 0 && <UnscannedFiles scan={scan} />}
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

function FindingsSection({ product, scan }: { product: Product; scan: Scan }) {
  const findings = useFindings(product)
  return (
    <section aria-labelledby="findings-heading" className="flex flex-col gap-3">
      <h2 id="findings-heading" className="text-h2">
        {t('screen.product.detail.findingsHeading')}
      </h2>
      {product.openFindings > 0 ? (
        <>
          <div role="alert" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4">
            <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
            <div className="flex flex-col gap-0.5">
              <p className="text-label font-semibold">{t('screen.product.detail.findingsOpen', { n: product.openFindings })}</p>
              <p className="text-body-sm">{t('screen.product.detail.findingsOpenBody')}</p>
            </div>
          </div>
          <RegionState query={findings} skeleton={<Skeleton className="h-32 w-full" />}>
            <ul aria-label={t('screen.product.detail.findingsList')} className="flex flex-col gap-3">
              {(findings.data ?? []).map((finding) => (
                <FindingCard key={finding.id} finding={finding} />
              ))}
            </ul>
          </RegionState>
        </>
      ) : scan.status === 'SUCCEEDED' ? (
        <div className="flex items-center gap-3 rounded-lg border border-border bg-card p-4">
          <CircleCheck aria-hidden="true" className="size-5 shrink-0 text-success" />
          <p className="text-body-sm">{t('screen.product.detail.clean')}</p>
        </div>
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.product.detail.findingsPending')}</p>
      )}
      <p className="text-caption text-muted-foreground">{t('screen.product.detail.limits')}</p>
    </section>
  )
}

/** One Finding (UX-DR31): the plain type, where it was found, and the masked value, with no reveal or copy. */
function FindingCard({ finding }: { finding: Finding }) {
  return (
    <li className="flex flex-col gap-2 rounded-lg border border-l-4 border-border border-l-destructive bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-h3 break-words">{finding.type}</h3>
        <span className="rounded-full bg-destructive-soft px-2 py-0.5 text-caption font-semibold text-destructive">
          {t('screen.product.detail.findingOpen')}
        </span>
      </div>
      <p className="text-body-sm text-muted-foreground">
        {t('screen.product.detail.findingFile')} <span className="font-mono break-all text-foreground">{finding.path}</span> ·{' '}
        {t('screen.product.detail.findingCommit')} <span className="font-mono text-foreground">{finding.commit.slice(0, 7)}</span>{' '}
        · {formatDate(finding.committedAt)}
      </p>
      <p className="text-body-sm">
        <span className="text-muted-foreground">{t('screen.product.detail.findingMasked')}</span>{' '}
        <span className="font-mono select-none">{finding.maskedPreview}</span>
      </p>
    </li>
  )
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
              {t('screen.product.detail.unscannedSize', { size: formatSize(file.sizeBytes) })}
            </span>
          </li>
        ))}
      </ul>
      {more > 0 && <p className="text-caption text-muted-foreground">{t('screen.product.detail.unscannedMore', { n: more })}</p>}
      <p className="text-caption text-muted-foreground">{t('screen.product.detail.unscannedHint')}</p>
    </section>
  )
}
