import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export interface DescriptionItem {
  term: ReactNode
  value: ReactNode
}

/**
 * Label and value pairs (a `<dl>`): the label in `{typography.body-sm}` muted, the value in the reading size, so a
 * reader can scan the labels. Side by side from 640 px, stacked below; `stacked` keeps them stacked everywhere, for
 * narrow columns.
 */
export function DescriptionList({
  items,
  stacked = false,
  className,
}: {
  items: DescriptionItem[]
  stacked?: boolean
  className?: string
}) {
  return (
    <dl
      className={cn(
        'grid gap-x-6 gap-y-3',
        !stacked && 'sm:grid-cols-[minmax(8rem,max-content)_minmax(0,1fr)] sm:gap-y-2',
        className,
      )}
    >
      {items.map((item, index) => (
        <div key={index} className={cn('flex min-w-0 flex-col gap-0.5', !stacked && 'sm:contents')}>
          <dt className="text-body-sm text-muted-foreground">{item.term}</dt>
          <dd className="m-0 min-w-0 break-words">{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}
