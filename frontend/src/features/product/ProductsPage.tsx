import { Package } from 'lucide-react'
import { cardInteractive } from '@/components/ui/card-surface'
import { cn } from '@/lib/utils'
import { Link, useLocation } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { SourceAppGuidance, type DeletedProduct } from './DeleteProduct'
import { useJobs, type JobSummary } from '@/features/job/jobApi'
import { useProducts, type Product } from './productApi'
import { productJobCounts, productJobsText } from './productJobs'
import { SourceStatusBadge } from './WorkflowSetup'
import { Page } from '@/components/ui/page'
import { Card } from '@/components/ui/card'

/**
 * CUS-2 (`/san-pham`, FR-7, UX-DR66): the user's own Products, with no role needed (Story 20.3), each with
 * its Source Repository, its Workflow status badge (the recorded status, read with no GitHub call, Story 23.5c), and
 * its Jobs (counted from the user's own Job list); with none, the empty state and "Đăng ký sản phẩm". vibefix scans no
 * code, so no scan state is shown.
 */
export function ProductsPage() {
  usePageTitle(t('screen.product.title'))
  return <ProductList />
}

function ProductList() {
  const products = useProducts()
  const jobs = useJobs()
  const items = products.data ?? []
  // R23: after a deletion, the Source App guidance stays on CUS-2 until the page is left.
  const deleted = (useLocation().state as { deleted?: DeletedProduct } | null)?.deleted
  return (
    <Page>
      <PageHeading inNavigation>{t('screen.product.heading')}</PageHeading>
      {items.length > 0 && (
        <div className="flex justify-end">
          <Link to="/san-pham/moi" className={buttonVariants()}>
            {t('screen.product.register')}
          </Link>
        </div>
      )}
      {deleted?.sourceApp && (
        <section role="status" className="flex flex-col gap-2 rounded-md border-l-4 border-info bg-info-soft p-4">
          <p className="text-label font-semibold break-words">
            {t('screen.product.detail.deletedGuidance', { product: deleted.name })}
          </p>
          <SourceAppGuidance repository={deleted.repository} sourceApp={deleted.sourceApp} />
        </section>
      )}
      <RegionState query={products} skeleton={<Skeleton className="h-48 w-full" />}>
        {items.length === 0 ? (
          <Card padding="none" dashed className="flex flex-col items-center gap-3 px-4 py-10 text-center">
            <Package aria-hidden="true" className="size-8 text-muted-foreground" />
            <p className="text-h3">{t('screen.product.empty')}</p>
            <p className="max-w-md text-body-sm text-muted-foreground">{t('screen.product.emptyHint')}</p>
            <Link to="/san-pham/moi" className={buttonVariants()}>
              {t('screen.product.register')}
            </Link>
          </Card>
        ) : (
          <ul aria-label={t('screen.product.listLabel')} className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {items.map((product) => (
              <ProductCard key={product.id} product={product} jobs={jobs.data} />
            ))}
          </ul>
        )}
      </RegionState>
    </Page>
  )
}

function ProductCard({ product, jobs }: { product: Product; jobs?: JobSummary[] }) {
  return (
    <Card as="li" className={cn('relative flex h-full flex-col gap-2', cardInteractive)}>
      <div className="flex items-start gap-3">
        <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary-soft text-primary">
          <Package className="size-5" />
        </span>
        <div className="flex min-w-0 grow flex-col items-start gap-1">
          {/* The whole card opens the Product: the link's hit area covers it. */}
          <Link
            to={`/san-pham/${product.id}`}
            className="text-h3 break-words text-inherit no-underline after:absolute after:inset-0 after:rounded-lg hover:underline"
          >
            {product.name}
          </Link>
          <SourceStatusBadge status={product.sourceStatus} />
        </div>
      </div>
      <p className="text-body-sm break-words text-muted-foreground">{product.description}</p>
      <p className="text-caption text-muted-foreground">
        {t('screen.product.source', { name: product.repositoryFullName })} {t('screen.product.domain', { domain: 'github.com' })}
      </p>
      <p className="text-caption text-muted-foreground">
        {t('screen.product.registeredOn', { date: formatDayMonth(product.createdAt) })}
        {jobs && ` · ${productJobsText(productJobCounts(jobs, product.id))}`}
      </p>
    </Card>
  )
}
