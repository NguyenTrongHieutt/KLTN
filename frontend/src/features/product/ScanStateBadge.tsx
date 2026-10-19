import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'
import type { Product } from './productApi'

const TONES: Record<Product['scanState'], string> = {
  AWAITING_SCAN: 'bg-muted text-foreground',
  SCANNING: 'bg-primary-soft text-primary',
  NOT_FINISHED: 'bg-warning-soft text-warning',
  FINDINGS: 'bg-destructive-soft text-destructive',
  CLEAN: 'bg-success-soft text-success',
}

/** A Product's scan state on CUS-2 and CUS-4 (FR-8): waiting, checking, not finished, open Findings, or clean. */
export function ScanStateBadge({ product, className }: { product: Product; className?: string }) {
  return (
    <span className={cn('w-fit rounded-full px-2 py-0.5 text-caption font-semibold', TONES[product.scanState], className)}>
      {t(`screen.product.state${product.scanState}` as MessageKey, { n: product.openFindings })}
    </span>
  )
}
