import { Package } from 'lucide-react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { useProducts, type Product } from './productApi'
import { ScanStateBadge } from './ScanStateBadge'

/**
 * CUS-2 (`/san-pham`, FR-7, FR-8, UX-DR66): the user's own Products, with no role needed (Story 20.3),
 * each with its scan state and open Findings, its Source Repository, and its Jobs; with none, the empty
 * state and "Đăng ký sản phẩm". Job counts come with Story 3.1.
 */
export function ProductsPage() {
  usePageTitle(t('screen.product.title'))
  return <ProductList />
}

function ProductList() {
  const products = useProducts()
  const items = products.data ?? []
  return (
    <div className="mx-auto flex max-w-[880px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <PageHeading>{t('screen.product.heading')}</PageHeading>
          <p className="text-body-sm text-muted-foreground">{t('screen.product.lead')}</p>
        </div>
        {items.length > 0 && (
          <Link to="/san-pham/moi" className={buttonVariants()}>
            {t('screen.product.register')}
          </Link>
        )}
      </div>
      <RegionState query={products} skeleton={<Skeleton className="h-48 w-full" />}>
        {items.length === 0 ? (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border bg-card p-10 text-center">
            <Package aria-hidden="true" className="size-8 text-muted-foreground" />
            <p className="text-h3">{t('screen.product.empty')}</p>
            <p className="max-w-md text-body-sm text-muted-foreground">{t('screen.product.emptyHint')}</p>
            <Link to="/san-pham/moi" className={buttonVariants()}>
              {t('screen.product.register')}
            </Link>
          </div>
        ) : (
          <ul aria-label={t('screen.product.listLabel')} className="flex flex-col gap-3">
            {items.map((product) => (
              <ProductCard key={product.id} product={product} />
            ))}
          </ul>
        )}
      </RegionState>
    </div>
  )
}

function ProductCard({ product }: { product: Product }) {
  return (
    <li className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-center gap-2">
        <Link to={`/san-pham/${product.id}`} className="text-h3 break-words text-primary underline-offset-4 hover:underline">
          {product.name}
        </Link>
        <ScanStateBadge product={product} />
      </div>
      <p className="text-body-sm break-words text-muted-foreground">{product.description}</p>
      <p className="text-caption text-muted-foreground">
        {t('screen.product.source', { name: product.repositoryFullName })} {t('screen.product.domain', { domain: 'github.com' })}
      </p>
      <p className="text-caption text-muted-foreground">
        {t('screen.product.registeredOn', { date: formatDayMonth(product.createdAt) })} · {t('screen.product.noJobs')}
      </p>
    </li>
  )
}
