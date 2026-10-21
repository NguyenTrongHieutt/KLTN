import { useId, useState } from 'react'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'

const TOOLS = ['LOVABLE', 'BOLT', 'REPLIT', 'V0', 'BASE44', 'CURSOR', 'CLAUDE_CODE', 'CODEX', 'OTHER'] as const

/**
 * "Cách tạm dừng đồng bộ" (N-6, FR-20): how to keep each AI builder from changing the Source Repository
 * while the Job runs, opened in place. Draft per-builder text until OQ7.
 */
export function PauseSyncHelp() {
  const ids = useId()
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button
        type="button"
        variant="ghost"
        size="compact"
        className="self-start"
        aria-expanded={open}
        aria-controls={`${ids}-pause`}
        onClick={() => setOpen(!open)}
      >
        {t('screen.agreement.review.pauseLink')}
      </Button>
      {open && (
        <div id={`${ids}-pause`} className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
          <p>{t('screen.agreement.review.pauseLead')}</p>
          <ul className="flex list-disc flex-col gap-1 pl-5">
            {TOOLS.map((tool) => (
              <li key={tool}>{t(`screen.agreement.review.pauseHelp.${tool}`)}</li>
            ))}
          </ul>
          <p className="text-caption text-muted-foreground">{t('screen.agreement.review.pauseDraft')}</p>
        </div>
      )}
    </>
  )
}
