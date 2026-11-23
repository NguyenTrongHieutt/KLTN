import { Check } from 'lucide-react'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'
import type { Job } from './jobApi'
import { currentStage, STAGES } from './stages'

/**
 * The 7-stage strip under a Job's title: done stages checked, the current one marked, later ones hollow. From 768 px
 * every stage shows its name; on phones the strip is seven segments under one line naming the current stage.
 */
export function StageStrip({ state }: { state: Job['state'] }) {
  const current = currentStage(state)
  if (current === undefined) {
    return null
  }
  const name = (stage: number) => t(`screen.public.landing.stages.${stage}` as MessageKey)
  const summary =
    current > STAGES.length
      ? t('screen.job.stages.done')
      : t('screen.job.stages.current', { n: current, total: STAGES.length, name: name(current) })
  return (
    <nav aria-label={t('screen.job.stages.label')} className="flex flex-col gap-2">
      <p className="text-caption text-muted-foreground md:hidden">{summary}</p>
      <ol className="flex gap-1 md:gap-0">
        {STAGES.map((stage) => {
          const done = stage < current
          const now = stage === current
          return (
            <li key={stage} aria-current={now ? 'step' : undefined} className="flex min-w-0 flex-1 flex-col gap-1.5">
              <span
                aria-hidden="true"
                className={cn('h-1 rounded-full md:hidden', done ? 'bg-success' : now ? 'bg-primary' : 'bg-border-strong')}
              />
              <span aria-hidden="true" className="hidden items-center md:flex">
                <span
                  className={cn(
                    'flex size-6 shrink-0 items-center justify-center rounded-full border text-caption font-semibold',
                    done && 'border-success bg-success text-success-foreground',
                    now && 'border-primary bg-primary text-primary-foreground',
                    !done && !now && 'border-border-strong bg-card text-muted-foreground',
                  )}
                >
                  {done ? <Check className="size-3.5" strokeWidth={3} /> : stage}
                </span>
                {stage < STAGES.length && (
                  <span className={cn('mx-1.5 h-px grow', done ? 'bg-success' : 'bg-border-strong')} />
                )}
              </span>
              <span
                aria-hidden="true"
                className={cn(
                  'hidden pr-3 text-caption leading-snug md:block',
                  now ? 'font-semibold text-foreground' : done ? 'text-foreground' : 'text-muted-foreground',
                )}
              >
                {name(stage)}
              </span>
              <span className="sr-only">
                {t(done ? 'screen.job.stages.srDone' : now ? 'screen.job.stages.srNow' : 'screen.job.stages.srLater', {
                  n: stage,
                  name: name(stage),
                })}
              </span>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
