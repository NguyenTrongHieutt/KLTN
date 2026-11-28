import { Dialog } from '@base-ui/react/dialog'
import { useId, useState } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useCancellation,
  useCancellationCommand,
  type Cancellation,
  type CancellationProposal,
} from './cancellationApi'
import { DialogBackdrop, DialogPopup } from '@/components/ui/dialog'
import { Card } from '@/components/ui/card'

/**
 * JOB-1's "Hủy công việc" (FR-40, UX-DR16): either side proposes cancelling the Job or its remaining
 * Milestones with a reason; the other side confirms through a destructive Confirmation dialog that
 * explains the full refund of held money; the proposer may withdraw. After the Agreement or Starting
 * deadline either side may cancel alone (FR-40). The active Crew reads it. In a Self-mentored Job whose Held Milestone
 * has no member, the no-member banner holds the way out (R49, UX-DR124), so nothing is proposed from here.
 * Elsewhere in a Self-mentored Job (Story 25.7, R14, R56, R57): with no Held Milestone the Customer, who is also its
 * Mentor, cancels at once on their word alone; with one, the proposal waits for every member who was active when it was
 * sent, each of whom answers once (agree or refuse), the Customer reads "Chờ k/n thành viên đồng ý", and a refusal
 * ends it. Nobody confirms it: "Xác nhận hủy" does not exist in the mode.
 */
export function CancellationSection({ jobId }: { jobId: string }) {
  const cancellation = useCancellation(jobId)
  const data = cancellation.data
  // An auxiliary part of the overview: nothing while loading, when the read fails, or with nothing to show.
  const canPropose = data?.canPropose === true && !data.noMember
  // A refused proposal of the mode stays readable: the members read their own answer, the Customer the way on.
  const refused = data?.selfMentored === true && data.proposal?.status === 'REFUSED'
  if (!data || (!canPropose && !data.canCancelAlone && !refused && (!data.proposal || data.proposal.status !== 'OPEN'))) {
    return null
  }
  return <Content jobId={jobId} data={data} />
}

function failure(error: unknown): string {
  return error instanceof ApiError && error.problem.code === 'STALE'
    ? t('screen.cancellation.stale')
    : problemMessage(error, { module: 'job' }).text
}

function Content({ jobId, data }: { jobId: string; data: Cancellation }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useCancellationCommand(jobId)
  const [proposing, setProposing] = useState(false)
  const [scope, setScope] = useState<'JOB' | 'REMAINING'>('JOB')
  const [reason, setReason] = useState('')
  const [asking, setAsking] = useState(false)
  const [alone, setAlone] = useState(false)
  // The mode's own confirmation of a proposal, before it is sent (a normal Job's proposal is sent directly).
  const [sending, setSending] = useState(false)
  const [answering, setAnswering] = useState<'agree' | 'refuse'>()
  const [answerError, setAnswerError] = useState<string>()
  const [error, setError] = useState<string>()
  const proposal = data.proposal?.status === 'OPEN' ? data.proposal : undefined
  const selfMentored = data.selfMentored
  // Self-mentored: the Customer cancels at once when nothing is held, and otherwise waits for the members' agreement.
  const atOnce = selfMentored && !data.held
  const refusedProposal = selfMentored && data.proposal?.status === 'REFUSED' ? data.proposal : undefined
  const run = (action: Parameters<typeof command.mutate>[0], success: MessageKey) => {
    setError(undefined)
    command.mutate(action, {
      onSuccess: () => {
        setAsking(false)
        setProposing(false)
        setAlone(false)
        setSending(false)
        toasts.success(t(success))
      },
      onError: (error) => {
        setAsking(false)
        setSending(false)
        setError(failure(error))
      },
    })
  }
  const answer = (agree: boolean) => {
    if (!proposal) {
      return
    }
    setAnswerError(undefined)
    command.mutate(
      { action: 'answer', proposalId: proposal.id, agree },
      {
        onSuccess: () => {
          setAnswering(undefined)
          toasts.success(t(agree ? 'screen.cancellation.self.agreedToast' : 'screen.cancellation.self.refusedToast'))
        },
        onError: (error) => {
          // Another proposal is open than the one shown: read it again before answering anything.
          if (error instanceof ApiError && error.problem.code === 'STALE') {
            setAnswering(undefined)
            setError(failure(error))
          } else {
            setAnswerError(failure(error))
          }
        },
      },
    )
  }
  const party = data.audience !== 'CREW'
  // A counted member of a Self-mentored Job reads the proposal and answers it; a normal Job's Crew only reads.
  const member = selfMentored && !party
  const lead = data.canCancelAlone
    ? 'screen.cancellation.aloneLead'
    : member
      ? 'screen.cancellation.self.leadMember'
      : selfMentored
        ? data.held
          ? 'screen.cancellation.self.leadHeld'
          : 'screen.cancellation.self.leadNow'
        : 'screen.cancellation.lead'
  return (
    <Card as="section"
      id="huy-cong-viec"
      aria-labelledby={`${ids}-title`}
      className="flex flex-col gap-3"
    >
      <h2 id={`${ids}-title`} className="text-h3">
        {t('screen.cancellation.heading')}
      </h2>
      <p className="text-body-sm text-muted-foreground">{t(lead)}</p>
      {proposal && (
        <div className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
          <span className="text-label">
            {t(`screen.cancellation.openBy.${proposal.proposer}` as MessageKey)} ·{' '}
            {t(proposal.scope === 'REMAINING' ? 'screen.cancellation.scopeREMAINING' : 'screen.cancellation.scopeJOB')}
          </span>
          <p className="break-words whitespace-pre-line">{proposal.reason}</p>
          {selfMentored ? (
            <SelfMentoredStatus proposal={proposal} member={member} />
          ) : (
            <span className="text-caption text-muted-foreground">
              {t('screen.cancellation.answerBy', { date: formatDayMonth(proposal.answerBy) })}
            </span>
          )}
          {proposal.unanswered && (!member || proposal.mayAnswer) && (
            <p className="flex flex-wrap items-center gap-2">
              {t(proposal.mine ? 'screen.cancellation.unanswered' : 'screen.cancellation.unansweredOther')}
              <a href="#tranh-chap" className="text-primary underline-offset-4 hover:underline">
                {t('screen.cancellation.openDispute')}
              </a>
            </p>
          )}
        </div>
      )}
      {refusedProposal && (
        <div className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
          <p className="break-words whitespace-pre-line">{refusedProposal.reason}</p>
          {member && refusedProposal.myAnswer && (
            <p className="text-label">
              {refusedProposal.myAnswer === 'REFUSED'
                ? t('screen.cancellation.self.youRefused')
                : t('screen.cancellation.self.youAgreed', {
                    agreed: refusedProposal.agreed ?? 0,
                    asked: refusedProposal.asked ?? 0,
                  })}
            </p>
          )}
          <p className="flex flex-wrap items-center gap-2">
            {t(member ? 'screen.cancellation.self.refusedMember' : 'screen.cancellation.self.refused')}
            <a href="#tranh-chap" className="text-primary underline-offset-4 hover:underline">
              {t('screen.cancellation.openDispute')}
            </a>
          </p>
        </div>
      )}
      {alone && (
        <fieldset className="flex flex-col gap-2 rounded-md border border-border bg-background p-3 text-body-sm">
          <legend className="px-1 text-label">{t('screen.cancellation.alone')}</legend>
          <label htmlFor={`${ids}-alone`} className="text-label">
            {t('screen.cancellation.reason')}
          </label>
          <textarea
            id={`${ids}-alone`}
            value={reason}
            maxLength={2000}
            onChange={(event) => setReason(event.target.value)}
            className={cn(inputClass, 'min-h-18')}
          />
          <div className="flex flex-wrap justify-end gap-3">
            <Button type="button" variant="ghost" onClick={() => setAlone(false)}>
              {t('screen.cancellation.cancel')}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={reason.trim() === '' || command.isPending}
              onClick={() => setAsking(true)}
            >
              {t('screen.cancellation.aloneSend')}
            </Button>
          </div>
        </fieldset>
      )}
      {proposing && (
        <fieldset className="flex flex-col gap-2 rounded-md border border-border bg-background p-3 text-body-sm">
          <legend className="px-1 text-label">{t('screen.cancellation.scope')}</legend>
          {(['JOB', 'REMAINING'] as const).map((value) => (
            <label key={value} className="flex min-h-tap-min md:min-h-control-compact items-center gap-2">
              <input
                type="radio"
                name={`${ids}-scope`}
                className="size-4"
                checked={scope === value}
                onChange={() => setScope(value)}
              />
              {t(value === 'JOB' ? 'screen.cancellation.scopeJOB' : 'screen.cancellation.scopeREMAINING')}
            </label>
          ))}
          <label htmlFor={`${ids}-reason`} className="text-label">
            {t('screen.cancellation.reason')}
          </label>
          <textarea
            id={`${ids}-reason`}
            value={reason}
            maxLength={2000}
            onChange={(event) => setReason(event.target.value)}
            className={cn(inputClass, 'min-h-18')}
          />
          <div className="flex flex-wrap justify-end gap-3">
            <Button type="button" variant="ghost" onClick={() => setProposing(false)}>
              {t('screen.cancellation.cancel')}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={reason.trim() === '' || command.isPending}
              onClick={() =>
                selfMentored
                  ? setSending(true)
                  : run({ action: 'propose', scope, reason: reason.trim(), version: data.version }, 'screen.cancellation.sent')
              }
            >
              {t(atOnce ? 'screen.cancellation.self.nowOpen' : 'screen.cancellation.send')}
            </Button>
          </div>
        </fieldset>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {party && (
        <div className="flex flex-wrap justify-end gap-3">
          {data.canCancelAlone && !alone && !proposing && (
            <Button type="button" variant="destructive" onClick={() => setAlone(true)}>
              {t('screen.cancellation.alone')}
            </Button>
          )}
          {data.canPropose && !data.noMember && !proposing && !alone && (
            <Button type="button" variant="secondary" onClick={() => setProposing(true)}>
              {t(atOnce ? 'screen.cancellation.self.nowOpen' : 'screen.cancellation.propose')}
            </Button>
          )}
          {proposal?.mine && (
            <Button
              type="button"
              variant="ghost"
              disabled={command.isPending}
              onClick={() => run({ action: 'withdraw' }, 'screen.cancellation.withdrawn')}
            >
              {t('screen.cancellation.withdraw')}
            </Button>
          )}
          {proposal && !proposal.mine && !selfMentored && (
            <Button type="button" variant="destructive" disabled={command.isPending} onClick={() => setAsking(true)}>
              {t('screen.cancellation.confirm')}
            </Button>
          )}
        </div>
      )}
      {member && proposal?.mayAnswer && (
        <div className="flex flex-wrap justify-end gap-3">
          <Button type="button" variant="secondary" disabled={command.isPending} onClick={() => setAnswering('refuse')}>
            {t('screen.cancellation.self.refuse')}
          </Button>
          <Button type="button" variant="destructive" disabled={command.isPending} onClick={() => setAnswering('agree')}>
            {t('screen.cancellation.self.agree')}
          </Button>
        </div>
      )}
      <Dialog.Root open={asking} onOpenChange={(next) => !next && setAsking(false)}>
        <Dialog.Portal>
          <DialogBackdrop className="z-[60]" />
          <DialogPopup size="sm" className="z-[70]">
            <Dialog.Title className="text-h2">
              {t(alone ? 'screen.cancellation.aloneTitle' : 'screen.cancellation.confirmTitle')}
            </Dialog.Title>
            <Dialog.Description className="flex flex-col gap-2 text-body-sm">
              {data.held && <span>{t('screen.cancellation.confirmHeld')}</span>}
              <span>{t('screen.cancellation.confirmPlanned')}</span>
              <span>{t('screen.cancellation.confirmEnd')}</span>
            </Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setAsking(false)}>
                {t('screen.cancellation.cancel')}
              </Button>
              <Button
                type="button"
                variant="destructive"
                disabled={command.isPending}
                onClick={() =>
                  run(
                    alone
                      ? { action: 'alone', reason: reason.trim(), version: data.version }
                      : { action: 'confirm', version: data.version },
                    'screen.cancellation.confirmed',
                  )
                }
              >
                {t(alone ? 'screen.cancellation.aloneSend' : 'screen.cancellation.confirm')}
              </Button>
            </div>
          </DialogPopup>
        </Dialog.Portal>
      </Dialog.Root>
      {selfMentored && (
        <Dialog.Root open={sending} onOpenChange={(next) => !next && setSending(false)}>
          <Dialog.Portal>
            <DialogBackdrop className="z-[60]" />
            <DialogPopup size="sm" className="z-[70]">
              <Dialog.Title className="text-h2">
                {t(atOnce ? 'screen.cancellation.self.nowTitle' : 'screen.cancellation.self.askTitle')}
              </Dialog.Title>
              <Dialog.Description className="flex flex-col gap-2 text-body-sm">
                {atOnce ? (
                  <>
                    <span>{t('screen.cancellation.self.nowBody')}</span>
                    <span>{t('screen.cancellation.confirmPlanned')}</span>
                    <span>{t('screen.cancellation.self.nowEnd')}</span>
                  </>
                ) : (
                  <>
                    {/* The answer date is the server's, fixed when the proposal is sent: none is shown before. */}
                    <span>{t('screen.cancellation.self.askBody')}</span>
                    <span>{t('screen.cancellation.self.askRefund')}</span>
                    <span>{t('screen.cancellation.self.askRefusal')}</span>
                  </>
                )}
              </Dialog.Description>
              <div className="flex flex-wrap justify-end gap-3">
                <Button type="button" variant="secondary" onClick={() => setSending(false)}>
                  {t('screen.cancellation.cancel')}
                </Button>
                <Button
                  type="button"
                  variant="destructive"
                  disabled={command.isPending}
                  onClick={() =>
                    run(
                      { action: 'propose', scope, reason: reason.trim(), version: data.version },
                      atOnce ? 'screen.cancellation.confirmed' : 'screen.cancellation.sent',
                    )
                  }
                >
                  {t(atOnce ? 'screen.cancellation.aloneSend' : 'screen.cancellation.send')}
                </Button>
              </div>
            </DialogPopup>
          </Dialog.Portal>
        </Dialog.Root>
      )}
      {member && (
        <Dialog.Root
          open={answering !== undefined}
          onOpenChange={(next) => {
            if (!next) {
              setAnswering(undefined)
              setAnswerError(undefined)
            }
          }}
        >
          <Dialog.Portal>
            <DialogBackdrop className="z-[60]" />
            <DialogPopup size="sm" className="z-[70]">
              <Dialog.Title className="text-h2">
                {t(answering === 'refuse' ? 'screen.cancellation.self.refuseTitle' : 'screen.cancellation.self.agreeTitle')}
              </Dialog.Title>
              <Dialog.Description className="flex flex-col gap-2 text-body-sm">
                <span>{t(answering === 'refuse' ? 'screen.cancellation.self.refuseBody' : 'screen.cancellation.self.agreeBody')}</span>
                <span>{t('screen.cancellation.self.answerFinal')}</span>
              </Dialog.Description>
              {answerError && (
                <p role="alert" className="text-body-sm text-destructive">
                  {answerError}
                </p>
              )}
              <div className="flex flex-wrap justify-end gap-3">
                <Button type="button" variant="secondary" onClick={() => setAnswering(undefined)}>
                  {t('screen.cancellation.cancel')}
                </Button>
                <Button
                  type="button"
                  variant={answering === 'refuse' ? 'secondary' : 'destructive'}
                  disabled={command.isPending}
                  onClick={() => answer(answering !== 'refuse')}
                >
                  {t(answering === 'refuse' ? 'screen.cancellation.self.refuse' : 'screen.cancellation.self.agree')}
                </Button>
              </div>
            </DialogPopup>
          </Dialog.Portal>
        </Dialog.Root>
      )}
    </Card>
  )
}

/**
 * What an open cancellation proposal of a Self-mentored Job says about the members' answers (Story 25.7): to the
 * Customer, how many of those asked agreed and by when (or that nobody is left to ask); to a member, the question
 * with its date, their own answer once given, or that they were not asked because they joined later.
 */
function SelfMentoredStatus({ proposal, member }: { proposal: CancellationProposal; member: boolean }) {
  const date = formatDayMonth(proposal.answerBy)
  const asked = proposal.asked ?? 0
  const agreed = proposal.agreed ?? 0
  const text = !member
    ? asked === 0
      ? t('screen.cancellation.self.nobody')
      : t('screen.cancellation.self.waiting', { agreed, asked, date })
    : proposal.mayAnswer
      ? t('screen.cancellation.self.memberAsk', { date })
      : proposal.myAnswer === 'AGREED'
        ? t('screen.cancellation.self.youAgreed', { agreed, asked })
        : proposal.myAnswer === 'REFUSED'
          ? t('screen.cancellation.self.youRefused')
          : t('screen.cancellation.self.notAsked')
  return <span className="text-caption text-muted-foreground">{text}</span>
}
