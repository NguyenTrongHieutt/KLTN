import { ExternalLink, Info } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate } from '@/lib/dates'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useProduct, type Product } from './productApi'
import { domainOf } from './productText'

/**
 * CUS-4 (`/san-pham/{productId}`, FR-7): the Product, its state, its Source Repository, and its
 * information. Scan history and Findings (Story 2.3), Jobs (Epic 3), and deletion (Story 2.8) come
 * with their stories.
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
          <span className="w-fit rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">
            {t(`screen.product.state${product.scanState}` as MessageKey)}
          </span>
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
      <div role="status" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4">
        <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
        <div className="flex flex-col gap-0.5">
          <p className="text-label font-semibold">{t('screen.product.detail.awaitingTitle')}</p>
          <p className="text-body-sm">{t('screen.product.detail.awaitingBody')}</p>
        </div>
      </div>
      <p className="text-body-sm break-words">{product.description}</p>
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
