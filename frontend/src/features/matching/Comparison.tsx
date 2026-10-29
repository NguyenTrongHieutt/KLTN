import { X } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import type { Histories, ReputationHistory } from '@/features/reputation/historyApi'
import { HistoryRegion } from '@/features/reputation/RoleHistories'
import { t } from '@/i18n/t'
import { useMatchMedia } from '@/lib/useMatchMedia'

/** Below this width the columns stack under a sticky header of names (UX-DR101). */
const WIDE = '(min-width: 1024px)'

/** One fixed row: its label, and its cell for one person; a cell of the histories in short waits for them. */
export interface ComparisonRow<T> {
  key: string
  label: ReactNode
  cell: (item: T, history: ReputationHistory | undefined) => ReactNode
  history?: boolean
}

/** A row's cell, behind the histories' Skeleton or error when it shows one of them. */
function cellOf<T>(row: ComparisonRow<T>, item: T, login: string | null | undefined, histories: Histories): ReactNode {
  const cell = row.cell(item, login ? histories.byUsername.get(login) : undefined)
  return row.history && login ? <HistoryRegion histories={histories}>{cell}</HistoryRegion> : cell
}

/**
 * The comparison of 2 or 3 people (UX-DR101), for CUS-13's bidders and JOB-9's applicants alike: the columns in the
 * order chosen, the rows in a fixed order, with no highlight, total, rank, or sort; "Chọn" under each column and
 * "Bỏ khỏi so sánh" beside each name. Below 1024 px the columns stack as cards under a sticky header of names.
 */
export function ComparisonView<T extends { id: string }>({
  heading,
  items,
  rows,
  name,
  extra,
  login,
  histories,
  chooseLabel,
  canChoose = () => true,
  onChoose,
  onRemove,
}: {
  heading: string
  items: T[]
  rows: ComparisonRow<T>[]
  name: (item: T) => string
  extra?: (item: T) => ReactNode
  login: (item: T) => string | null | undefined
  histories: Histories
  chooseLabel: string
  canChoose?: (item: T) => boolean
  onChoose: (item: T) => void
  onRemove: (item: T) => void
}) {
  const wide = useMatchMedia(WIDE)
  const title = (item: T) => (
    <span className="flex flex-wrap items-center justify-between gap-2">
      <span className="flex flex-wrap items-center gap-2">
        <span className="text-label font-semibold break-words">{name(item)}</span>
        {extra?.(item)}
      </span>
      <Button type="button" variant="ghost" size="compact" onClick={() => onRemove(item)}>
        <X aria-hidden="true" className="size-4" />
        {t('screen.matching.compare.remove')}
      </Button>
    </span>
  )
  if (wide) {
    return (
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <table className="w-full table-fixed border-collapse text-body-sm">
          <caption className="sr-only">{heading}</caption>
          <thead>
            <tr>
              <td className="w-56 border-b border-border p-3" />
              {items.map((item) => (
                <th key={item.id} scope="col" className="border-b border-border p-3 text-left align-top">
                  {title(item)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.key} className="align-top">
                <th scope="row" className="border-b border-border p-3 text-left font-normal text-muted-foreground">
                  {row.label}
                </th>
                {items.map((item) => (
                  <td key={item.id} className="border-b border-border p-3 break-words">
                    {cellOf(row, item, login(item), histories)}
                  </td>
                ))}
              </tr>
            ))}
            <tr>
              <td className="p-3" />
              {items.map((item) => (
                <td key={item.id} className="p-3">
                  {canChoose(item) && (
                    <Button type="button" onClick={() => onChoose(item)}>
                      {chooseLabel}
                    </Button>
                  )}
                </td>
              ))}
            </tr>
          </tbody>
        </table>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-4">
      <ul
        aria-label={heading}
        className="sticky top-0 z-10 flex flex-wrap gap-x-4 gap-y-1 rounded-md border border-border bg-card px-4 py-2 text-label font-semibold shadow-sm"
      >
        {items.map((item) => (
          <li key={item.id} className="break-words">
            {name(item)}
          </li>
        ))}
      </ul>
      {items.map((item) => (
        <section key={item.id} aria-label={name(item)} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
          {title(item)}
          <dl className="flex flex-col gap-3 text-body-sm">
            {rows.map((row) => (
              <div key={row.key} className="flex flex-col gap-1">
                <dt className="text-caption text-muted-foreground">{row.label}</dt>
                <dd className="break-words">{cellOf(row, item, login(item), histories)}</dd>
              </div>
            ))}
          </dl>
          {canChoose(item) && (
            <Button type="button" className="self-start" onClick={() => onChoose(item)}>
              {chooseLabel}
            </Button>
          )}
        </section>
      ))}
    </div>
  )
}
