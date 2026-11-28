import { Dialog } from '@base-ui/react/dialog'
import { CircleCheck, Circle } from 'lucide-react'
import { useId, useState } from 'react'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import {
  useClosure,
  useClosureCommand,
  useConfirmDeletion,
  useDataDeletion,
  type Closure,
  type DataDeletion,
} from './closureApi'
import { ClosingChoice } from './ClosingChoice'
import { FinalMergeStep } from './FinalMergeStep'
import { RevocationChecklist } from './RevocationChecklist'
import { DialogBackdrop, DialogPopup } from '@/components/ui/dialog'
import { Card } from '@/components/ui/card'

type Action = 'propose' | 'confirm' | 'decline'

/**
 * JOB-1's closure section (FR-37, FR-67, UX-DR55): "Đề nghị kết thúc công việc" when the Customer or
 * the Mentor may propose it; the open proposal with its date and "Xác nhận kết thúc" or "Từ chối" for
 * whoever is to answer; and, from the proposal through Closed, each party's steps done or pending, the
 * data-deletion ones from `repo` (FR-66), with "Chọn cách nhận mã" on a Code Job (FR-37, FR-38) -- the Closing
 * choice cards for the Customer, above the Final Merge's own states, and the read-only line in their place for the
 * Mentor -- and, once the Job ends, their Revocation Checklist (FR-66).
 * A Crew member sees their own step only. The Mentor and the Crew
 * tick their data-deletion Checklist once the Job has ended. Proposing, confirming, and each tick ask
 * for confirmation first. In a Self-mentored Job the Customer is also the Mentor (Story 25.7): there is no second side,
 * so their closure takes effect at once on their word, the System's proposal asks only them, and declining it only
 * withdraws it.
 */
export function ClosureSection({ jobId }: { jobId: string }) {
  const closure = useClosure(jobId)
  const deletion = useDataDeletion(jobId)
  // An auxiliary part of the overview: it shows nothing while loading or when the read fails.
  return closure.data ? <Content jobId={jobId} closure={closure.data} deletion={deletion.data} /> : null
}

function Content({ jobId, closure, deletion }: { jobId: string; closure: Closure; deletion: DataDeletion | undefined }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useClosureCommand(jobId)
  const [asking, setAsking] = useState<Action>()
  const [error, setError] = useState<string>()
  // The Customer of a Self-mentored Job is its Mentor too: the view says so, in the same read as the rest.
  const alone = closure.selfMentored && closure.audience === 'CUSTOMER'
  const proposal = closure.proposal
  const open = proposal?.status === 'OPEN'
  // The combined role is the Mentor too: a second, Mentor's step would name the same person twice.
  const steps = alone ? closure.steps.filter((step) => step.party !== 'MENTOR') : closure.steps
  if (!proposal && closure.steps.length === 0 && !closure.canPropose && !closure.disputable) {
    return null
  }

  const run = (action: Action) => {
    setError(undefined)
    command.mutate(
      { action, version: closure.version },
      {
        onSuccess: (next) => {
          setAsking(undefined)
          toasts.success(
            t(
              action === 'decline'
                ? alone
                  ? 'screen.closure.self.declined'
                  : 'screen.closure.declined'
                : next.state === 'ACTIVE'
                  ? action === 'propose'
                    ? 'screen.closure.proposed'
                    : 'screen.closure.confirmedOne'
                  : 'screen.closure.closed',
            ),
          )
        },
        onError: (failure) => {
          setAsking(undefined)
          const reason = failure instanceof ApiError ? failure.problem.reason : undefined
          setError(
            failure instanceof ApiError && failure.problem.code === 'STALE'
              ? t('screen.closure.stale')
              : reason === 'MILESTONE_HELD'
                ? t('screen.closure.held')
                : problemMessage(failure, { module: 'job' }).text,
          )
        },
      },
    )
  }

  return (
    <Card as="section" aria-labelledby={`${ids}-title`} className="flex flex-col gap-3">
      <h2 id={`${ids}-title`} className="text-h3">
        {t(closure.state === 'CLOSED' ? 'screen.closure.titleClosed' : 'screen.closure.title')}
      </h2>
      {closure.state === 'CLOSED' && <p className="text-body-sm">{t('screen.closure.done')}</p>}
      {closure.state === 'CLOSING' && <p className="text-body-sm">{t('screen.closure.closing')}</p>}
      {open && proposal && (
        <p className="text-body-sm">
          {proposal.kind === 'AUTOMATIC'
            ? t('screen.closure.automatic', { date: formatDayMonth(proposal.autoConfirmAt ?? proposal.answerBy) })
            : t(`screen.closure.proposedBy.${proposal.proposer}` as MessageKey, { date: formatDayMonth(proposal.answerBy) })}
          {alone && proposal.answerable && <> {t('screen.closure.self.oneAnswer')}</>}
        </p>
      )}
      {steps.length > 0 &&
        (closure.state === 'CLOSED' ? (
          // Once the Job has ended, the finished steps fold into one line that opens on demand (2026-11-23).
          <details className="group text-body-sm">
            <summary className="w-fit cursor-pointer text-primary underline-offset-4 hover:underline">
              {t('screen.closure.stepsSummary', {
                done: steps.filter((step) => step.done).length + (deletion?.parties.filter((party) => party.confirmed).length ?? 0),
                total: steps.length + (deletion?.parties.length ?? 0),
              })}
            </summary>
            <div className="mt-2">
              <ul className="flex flex-col gap-1.5 text-body-sm">
                {steps.map((step, index) => (
                  <Step
                    key={`${step.party}-${index}`}
                    done={step.done}
                    text={t(`screen.closure.step.${step.party}.${step.step}` as MessageKey, { name: step.name })}
                  />
                ))}
                {deletion?.parties.map((party, index) => (
                  <Step
                    key={`deletion-${index}`}
                    done={party.confirmed}
                    text={t(`screen.closure.deletion.${party.party}` as MessageKey, { name: party.name })}
                  />
                ))}
              </ul>
            </div>
          </details>
        ) : (
          <ul className="flex flex-col gap-1.5 text-body-sm">
            {steps.map((step, index) => (
              <Step
                key={`${step.party}-${index}`}
                done={step.done}
                text={t(`screen.closure.step.${step.party}.${step.step}` as MessageKey, { name: step.name })}
              />
            ))}
            {deletion?.parties.map((party, index) => (
              <Step
                key={`deletion-${index}`}
                done={party.confirmed}
                text={t(`screen.closure.deletion.${party.party}` as MessageKey, { name: party.name })}
              />
            ))}
          </ul>
        ))}
      {(closure.audience === 'CUSTOMER' || closure.audience === 'MENTOR')
        && (closure.steps.length > 0 || closure.state !== 'ACTIVE') && (
          <ClosingChoice jobId={jobId} version={closure.version} />
        )}
      {closure.audience === 'CUSTOMER' && (closure.steps.length > 0 || closure.state !== 'ACTIVE') && (
        <FinalMergeStep jobId={jobId} />
      )}
      {(closure.audience === 'CUSTOMER' || closure.audience === 'MENTOR') && closure.state !== 'ACTIVE' && (
        <RevocationChecklist jobId={jobId} />
      )}
      {deletion?.mine && (closure.steps.length > 0 || deletion.ended) && <DeletionChecklist jobId={jobId} deletion={deletion} />}
      {closure.disputable && (
        <p className="flex flex-wrap items-center gap-2 text-body-sm">
          {t('screen.closure.disputable')}
          <a href="#tranh-chap" className="text-primary underline-offset-4 hover:underline">
            {t('screen.closure.openDispute')}
          </a>
        </p>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {alone && open && proposal?.answerable && (
        <p className="text-caption text-muted-foreground">{t('screen.closure.self.declineNote')}</p>
      )}
      <div className="flex flex-wrap gap-3">
        {open && proposal?.answerable && (
          <>
            <Button type="button" onClick={() => setAsking('confirm')} disabled={command.isPending}>
              {t('screen.closure.confirm')}
            </Button>
            <Button type="button" variant="secondary" onClick={() => run('decline')} disabled={command.isPending}>
              {t('screen.closure.decline')}
            </Button>
          </>
        )}
        {closure.canPropose && (
          <Button type="button" variant="secondary" onClick={() => setAsking('propose')} disabled={command.isPending}>
            {t(alone ? 'screen.closure.self.propose' : 'screen.closure.propose')}
          </Button>
        )}
      </div>
      <Dialog.Root open={asking !== undefined} onOpenChange={(next) => !next && setAsking(undefined)}>
        <Dialog.Portal>
          <DialogBackdrop className="z-[60]" />
          <DialogPopup size="sm" className="z-[70]">
            <Dialog.Title className="text-h2">
              {t(
                asking === 'propose'
                  ? alone
                    ? 'screen.closure.self.proposeTitle'
                    : 'screen.closure.proposeTitle'
                  : 'screen.closure.confirmTitle',
              )}
            </Dialog.Title>
            <Dialog.Description className="text-body-sm">
              {t(asking === 'propose' && alone ? 'screen.closure.self.effects' : 'screen.closure.effects')}
            </Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setAsking(undefined)}>
                {t('screen.closure.cancel')}
              </Button>
              <Button type="button" disabled={command.isPending} onClick={() => asking && run(asking)}>
                {t(asking === 'propose' ? (alone ? 'screen.closure.self.propose' : 'screen.closure.propose') : 'screen.closure.confirm')}
              </Button>
            </div>
          </DialogPopup>
        </Dialog.Portal>
      </Dialog.Root>
    </Card>
  )
}

function Step({ done, text }: { done: boolean; text: string }) {
  return (
    <li className="flex items-center gap-2">
      {done ? (
        <CircleCheck aria-hidden="true" className="size-4 shrink-0 text-success" />
      ) : (
        <Circle aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
      )}
      <span className="break-words">{text}</span>
      <span className="text-caption text-muted-foreground">
        {t(done ? 'screen.closure.stepDone' : 'screen.closure.stepPending')}
      </span>
    </li>
  )
}

/**
 * The data-deletion Checklist (FR-66, UX-DR51): each tick asks first and is kept with the time. A member who left may
 * tick theirs before the Job ends (Story 21.6).
 */
export function DeletionChecklist({ jobId, deletion }: { jobId: string; deletion: DataDeletion }) {
  const ids = useId()
  const toasts = useToasts()
  const confirm = useConfirmDeletion(jobId)
  const [asking, setAsking] = useState<'NON_CODE_DATA' | 'PREVIEWS'>()
  const [error, setError] = useState<string>()
  return (
    <fieldset className="flex flex-col gap-2 rounded-md bg-muted p-3 text-body-sm">
      <legend className="text-label">{t('screen.closure.deletion.mine')}</legend>
      <p>{deletion.ended || deletion.left ? t('screen.closure.deletion.lead') : t('screen.closure.deletion.notYet')}</p>
      {(deletion.mine ?? []).map((row) => {
        const item = row.item as 'NON_CODE_DATA' | 'PREVIEWS'
        return (
          <label key={item} htmlFor={`${ids}-${item}`} className="flex min-h-tap-min md:min-h-control-compact items-center gap-2">
            <input
              id={`${ids}-${item}`}
              type="checkbox"
              className="size-4"
              checked={row.confirmedAt != null}
              disabled={row.confirmedAt != null || !(deletion.ended || deletion.left) || confirm.isPending}
              onChange={() => setAsking(item)}
            />
            <span className="break-words">{t(`screen.closure.deletion.${item}` as MessageKey)}</span>
            {row.confirmedAt && (
              <span className="text-caption text-muted-foreground">
                {t('screen.closure.deletion.confirmedAt', { time: formatDayMonthTime(row.confirmedAt) })}
              </span>
            )}
          </label>
        )
      })}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      <Dialog.Root open={asking !== undefined} onOpenChange={(next) => !next && setAsking(undefined)}>
        <Dialog.Portal>
          <DialogBackdrop className="z-[60]" />
          <DialogPopup size="sm" className="z-[70]">
            <Dialog.Title className="text-h2">{t('screen.closure.deletion.dialogTitle')}</Dialog.Title>
            <Dialog.Description className="text-body-sm">
              {asking && t(`screen.closure.deletion.${asking}` as MessageKey)}. {t('screen.closure.deletion.dialogBody')}
            </Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setAsking(undefined)}>
                {t('screen.closure.deletion.cancel')}
              </Button>
              <Button
                type="button"
                disabled={confirm.isPending}
                onClick={() =>
                  asking &&
                  confirm.mutate(asking, {
                    onSuccess: () => {
                      setAsking(undefined)
                      setError(undefined)
                      toasts.success(t('screen.closure.deletion.saved'))
                    },
                    onError: (failure) => {
                      setAsking(undefined)
                      setError(problemMessage(failure, { module: 'repo' }).text)
                    },
                  })
                }
              >
                {t('screen.closure.deletion.confirm')}
              </Button>
            </div>
          </DialogPopup>
        </Dialog.Portal>
      </Dialog.Root>
    </fieldset>
  )
}
