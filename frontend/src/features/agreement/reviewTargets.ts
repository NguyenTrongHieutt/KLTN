import { t } from '@/i18n/t'
import type { AgreementComment, AgreementContent } from './agreementApi'

/** What a comment is about, in words: "Giai đoạn 1 · …", "Tiêu chí 2 của giai đoạn 1", … */
export function targetLabel(content: AgreementContent, kind: AgreementComment['targetKind'], targetId?: string): string {
  if (kind === 'SCOPE') {
    return t('screen.agreement.review.target.SCOPE')
  }
  if (kind === 'EXTERNAL_ACCESS') {
    const access = content.externalAccess.find((entry) => entry.id === targetId)
    return access ? t('screen.agreement.review.target.EXTERNAL_ACCESS', { system: access.system }) : t('screen.agreement.review.target.GONE')
  }
  for (const [m, milestone] of content.milestones.entries()) {
    if (kind === 'MILESTONE' && milestone.id === targetId) {
      return t('screen.agreement.review.target.MILESTONE', { n: m + 1, title: milestone.title })
    }
    const c = milestone.criteria.findIndex((criterion) => criterion.id === targetId)
    if (kind === 'CRITERION' && c >= 0) {
      return t('screen.agreement.review.target.CRITERION', { c: c + 1, n: m + 1 })
    }
  }
  return t('screen.agreement.review.target.GONE')
}

/** The items in the given order of IDs; an item the order does not name keeps its place at the end. */
export function inOrder<T extends { id: string }>(items: T[], order: string[]): T[] {
  const byId = new Map(items.map((item) => [item.id, item]))
  const ordered = order.flatMap((id) => {
    const item = byId.get(id)
    return item ? [item] : []
  })
  return [...ordered, ...items.filter((item) => !order.includes(item.id))]
}

/** The order with the item at `index` moved one place up or down. */
export function moved(order: string[], index: number, delta: -1 | 1): string[] {
  const target = index + delta
  if (target < 0 || target >= order.length) {
    return order
  }
  const next = [...order]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item as string)
  return next
}

/** The draft's timeline in days, from J4 to the last Milestone due date (FR-16's comparison). */
export function draftDays(startedAt: string | undefined, dueDates: (string | undefined)[]): number | undefined {
  const last = dueDates.filter((due): due is string => !!due).sort().at(-1)
  if (!startedAt || !last) {
    return undefined
  }
  const start = new Date(startedAt)
  const startDay = Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), start.getUTCDate())
  const [y, mo, d] = last.split('-').map(Number)
  return Math.max(0, Math.round((Date.UTC(y ?? 0, (mo ?? 1) - 1, d ?? 1) - startDay) / 86_400_000))
}
