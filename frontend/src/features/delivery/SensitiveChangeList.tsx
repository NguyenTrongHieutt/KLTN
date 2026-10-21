import { TriangleAlert } from 'lucide-react'
import type { components } from '@/api/schema'
import { TermHint } from '@/components/TermHint'
import { t, type MessageKey } from '@/i18n/t'

type SensitiveChange = components['schemas']['SensitiveChangeView']

/**
 * The Sensitive change list (FR-32, FR-37, SG-19, UX-DR35): each changed file that can run code on the
 * Customer's systems, its path in mono and one plain explanation of its kind, or a line saying there is
 * none. The handover form previews it; the Delivery Report and the Final Merge show it. `level` is the
 * heading's level where it sits.
 */
export function SensitiveChangeList({
  changes,
  headingId,
  level = 3,
}: {
  changes: SensitiveChange[]
  headingId: string
  level?: 2 | 3
}) {
  const Heading = level === 2 ? 'h2' : 'h3'
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-2">
      <Heading id={headingId} className={level === 2 ? 'text-h2' : 'text-h3'}>
        <TermHint term="sensitiveChange" />
      </Heading>
      {changes.length === 0 ? (
        <p className="text-body-sm text-muted-foreground">{t('screen.handover.code.sensitiveNone')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {changes.map((change) => (
            <li key={change.path} className="flex gap-2 rounded-md border border-border bg-card p-3 text-body-sm">
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warning" />
              <span className="flex min-w-0 flex-col gap-0.5">
                <code className="font-mono text-caption break-all">{change.path}</code>
                <span>{t(`screen.handover.code.kind${change.kind}` as MessageKey)}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
