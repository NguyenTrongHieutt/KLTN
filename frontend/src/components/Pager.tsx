import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

/** A result list's pages (MEN-2, CUS-8): "Trước", at most five pages around the current one, and "Sau"; from 0. */
export function Pager({ page, pages, go }: { page: number; pages: number; go: (page: number) => void }) {
  if (pages <= 1) {
    return null
  }
  const from = Math.max(0, Math.min(page - 2, pages - 5))
  const shown = Array.from({ length: Math.min(5, pages) }, (_, index) => from + index)
  return (
    <nav aria-label={t('screen.jobPost.pages')} className="flex flex-wrap justify-center gap-1">
      <Button type="button" variant="secondary" disabled={page === 0} onClick={() => go(page - 1)}>
        {t('screen.jobPost.previous')}
      </Button>
      {shown.map((number) => (
        <Button
          key={number}
          type="button"
          variant={number === page ? 'primary' : 'ghost'}
          aria-current={number === page ? 'page' : undefined}
          aria-label={t('screen.jobPost.page', { n: number + 1 })}
          className={cn(number === page && 'pointer-events-none')}
          onClick={() => go(number)}
        >
          {number + 1}
        </Button>
      ))}
      <Button type="button" variant="secondary" disabled={page >= pages - 1} onClick={() => go(page + 1)}>
        {t('screen.jobPost.next')}
      </Button>
    </nav>
  )
}
