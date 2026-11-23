import { BriefcaseBusiness, ExternalLink } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { EmptyState } from '@/components/ui/empty-state'
import { useJobs } from '@/features/job/jobApi'
import { JobSummaryList } from '@/features/job/JobSummaryList'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate } from '@/lib/dates'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useProduct, type Product } from './productApi'
import { DeleteProduct } from './DeleteProduct'
import { ENDED_JOB_STATES } from './productJobs'
import { domainOf } from './productText'
import { SourceSetupCard } from './SourceSetupCard'
import { Page } from '@/components/ui/page'
import { Card } from '@/components/ui/card'

/**
 * CUS-4 (`/san-pham/{productId}`, FR-7): the Product, its Source Repository, its Jobs, and its information, with
 * "Đăng công việc" (CUS-6) open at once. vibefix does not scan code (FR-8, rewritten 2026-11-12), so
 * CUS-4 shows no scan result and no re-scan; the compact Workflow setup card shows the Source App and workflow status
 * with "Kiểm tra lại" (Story 23.5c). "Xóa sản phẩm" deletes it after a destructive Confirmation dialog.
 */
export function ProductPage() {
  const { productId } = useParams()
  const product = useProduct(productId)
  usePageTitle(product.data?.name ?? t('screen.product.title'))
  if (product.isError && isNotFound(product.error)) {
    return <NotFoundScreen />
  }
  return (
    <Page>
      <RegionState query={product} skeleton={<Skeleton className="h-64 w-full" />}>
        {product.data && <ProductDetail product={product.data} />}
      </RegionState>
    </Page>
  )
}

/** The Product's Jobs, running ones first, each linking to its Job (or a Draft to its form). */
function ProductJobs({ product }: { product: Product }) {
  const jobs = useJobs()
  const mine = (jobs.data ?? []).filter((job) => job.productId === product.id)
  const ordered = [
    ...mine.filter((job) => !ENDED_JOB_STATES.includes(job.state)),
    ...mine.filter((job) => ENDED_JOB_STATES.includes(job.state)),
  ]
  return (
    <section aria-labelledby="product-jobs-heading" className="flex flex-col gap-3">
      <h2 id="product-jobs-heading" className="text-h2">
        {t('screen.product.detail.jobsHeading')}
        {jobs.data && (
          <span className="ms-2 font-normal text-muted-foreground">{t('screen.job.groupCount', { n: mine.length })}</span>
        )}
      </h2>
      <RegionState query={jobs} skeleton={<Skeleton className="h-20 w-full rounded-lg" />}>
        {ordered.length > 0 ? (
          <JobSummaryList jobs={ordered} showProduct={false} label={t('screen.product.detail.jobsLabel')} />
        ) : (
          <EmptyState
            compact
            icon={BriefcaseBusiness}
            title={t('screen.product.detail.jobsEmpty')}
            description={t('screen.product.detail.jobsEmptyHint')}
          />
        )}
      </RegionState>
    </section>
  )
}

function ProductDetail({ product }: { product: Product }) {
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
          <Link to={`/san-pham/${product.id}/cong-viec-moi`} className={buttonVariants()}>
            {t('screen.product.detail.postJob')}
          </Link>
        </div>
      </div>
      <p className="text-body-sm break-words">{product.description}</p>
      <SourceSetupCard product={product} />
      <ProductJobs product={product} />
      <Card as="section" aria-labelledby="product-info-heading" className="flex flex-col gap-2">
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
      </Card>
      <DeleteProduct product={product} />
    </>
  )
}
