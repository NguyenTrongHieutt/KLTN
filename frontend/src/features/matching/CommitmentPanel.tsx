import { Dialog } from '@base-ui/react/dialog'
import { Clock, X } from 'lucide-react'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDayMonth, formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  COMMITMENT_VERSION,
  useCommitment,
  useDeclineCommitment,
  useSignCommitment,
  type Commitment,
  type CommitmentPanel as Panel,
} from './crewApplicationApi'
import { NDA_VERSION } from './nda'
import { CommitEmailNotice, NdaText } from './NdaText'

/** How an ended pick reads (UX-DR103): withdrawn, lapsed, ended by J8, declined, or joined. */
const ENDED: Partial<Record<string, MessageKey>> = {
  PICK_WITHDRAWN: 'screen.commitment.ended.PICK_WITHDRAWN',
  COMMITMENT_LAPSED: 'screen.commitment.ended.COMMITMENT_LAPSED',
  PICK_ENDED: 'screen.commitment.ended.PICK_ENDED',
  COMMITMENT_DECLINED: 'screen.commitment.ended.COMMITMENT_DECLINED',
  APPLICANT_UNAVAILABLE: 'screen.commitment.ended.APPLICANT_UNAVAILABLE',
}

/**
 * MEN-3's Commitment panel for a picked person (FR-81, AD-41, UX-DR103, Story 21.5), in place of the Crew Terms
 * assignment panel: N-19 with the GitHub username the Customer will learn, the assignment projection (the scope and the
 * Milestones with their prices), the split rules, N-14 with the NDA text, the commitment text with its version, N-5,
 * N-23 for a Code Job (asked once per notice version), and the chip "Ký trước {date}", with "Ký và tham gia" or "Từ
 * chối". Once the pick ended, it gives way to why, with "Tìm việc khác"; once signed, to the way into the Job. Nothing
 * else of the Job is read here.
 */
export function CommitmentPanel({ jobId }: { jobId: string }) {
  const commitment = useCommitment(jobId)
  return (
    <RegionState query={commitment} skeleton={<Skeleton className="h-96 w-full" />}>
      {commitment.data &&
        (commitment.data.status === 'PICKED' && commitment.data.panel ? (
          <Signing commitment={commitment.data} panel={commitment.data.panel} />
        ) : (
          <Ended commitment={commitment.data} />
        ))}
    </RegionState>
  )
}

function Signing({ commitment, panel }: { commitment: Commitment; panel: Panel }) {
  const ids = useId()
  const toasts = useToasts()
  const sign = useSignCommitment(commitment.jobId)
  const decline = useDeclineCommitment(commitment.jobId)
  const [publicCode, setPublicCode] = useState(false)
  const [understood, setUnderstood] = useState(false)
  const [declining, setDeclining] = useState(false)
  const [error, setError] = useState<string>()
  const needsNotice = panel.codeJob && !panel.commitEmailNoticeAcknowledged
  const ready = publicCode && (!needsNotice || understood)
  const due = formatDayMonthTime(commitment.signDueAt)
  const submit = () => {
    setError(undefined)
    sign.mutate(
      {
        ndaVersion: NDA_VERSION,
        commitmentVersion: COMMITMENT_VERSION,
        publicCodeAcknowledged: publicCode,
        commitEmailNoticeAcknowledged: understood || panel.commitEmailNoticeAcknowledged,
      },
      {
        onSuccess: () => toasts.success(t('screen.commitment.signed', { job: commitment.jobTitle })),
        onError: (failed) =>
          setError(
            failed instanceof ApiError && failed.problem.reason === 'PICK_ENDED'
              ? t('screen.commitment.ended.PICK_ENDED')
              : problemMessage(failed, { module: 'job', action: t('screen.commitment.signAction') }).text,
          ),
      },
    )
  }
  const confirmDecline = () =>
    decline.mutate(undefined, {
      onSuccess: () => {
        setDeclining(false)
        toasts.success(t('screen.commitment.declined'))
      },
      onError: (failed) => {
        setDeclining(false)
        toasts.error(problemMessage(failed, { module: 'matching' }).text)
      },
    })
  return (
    <section aria-labelledby={`${ids}-heading`} className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={`${ids}-heading`} className="text-h2">
          {t('screen.commitment.title')}
        </h2>
        <span className="flex items-center gap-1.5 rounded-full bg-warning-soft px-2.5 py-0.5 text-caption font-semibold">
          <Clock aria-hidden="true" className="size-3.5" />
          {t('screen.commitment.due', { date: due })}
        </span>
      </div>
      <div role="note" className="rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
        {t('screen.commitment.n19', {
          mentor: commitment.mentorName,
          job: commitment.jobTitle,
          date: due,
          login: panel.githubLogin ?? '',
        })}
      </div>
      <div className="flex flex-col gap-2">
        <h3 className="text-h3">{t('screen.commitment.scope')}</h3>
        <p className="text-body-sm break-words whitespace-pre-line">{panel.scope ?? t('screen.commitment.noScope')}</p>
      </div>
      <div className="flex flex-col gap-2">
        <h3 className="text-h3">{t('screen.commitment.milestones')}</h3>
        {panel.milestones.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.commitment.noMilestones')}</p>
        ) : (
          <ol className="flex flex-col divide-y divide-border rounded-md border border-border">
            {panel.milestones.map((milestone, index) => (
              <li key={`${index}-${milestone.title}`} className="flex flex-wrap items-baseline justify-between gap-2 px-4 py-2.5 text-body-sm">
                <span className="font-semibold break-words">
                  {t('screen.commitment.milestone', { n: index + 1, title: milestone.title })}
                </span>
                <span className="flex flex-wrap items-center gap-2 text-muted-foreground">
                  {milestone.dueOn && t('screen.commitment.milestoneDue', { date: formatCalendarDayMonth(milestone.dueOn) })}
                  {milestone.priceVnd != null && (
                    <span className="text-foreground">
                      {formatVnd(milestone.priceVnd)} <TestTag />
                    </span>
                  )}
                </span>
              </li>
            ))}
          </ol>
        )}
        <p className="text-caption text-muted-foreground">{t('screen.commitment.noShare')}</p>
      </div>
      <div className="flex flex-col gap-2">
        <h3 className="text-h3">{t('screen.commitment.splitRules')}</h3>
        <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
          <li>{t('screen.commitment.ruleCap', { n: panel.splitRules.mentorShareCapPercent })}</li>
          <li>{t('screen.commitment.ruleDraft', { n: panel.splitRules.draftDays })}</li>
          <li>{t('screen.commitment.ruleConfirm', { n: panel.splitRules.confirmationDays })}</li>
          <li>{t('screen.commitment.ruleEdits', { n: panel.splitRules.editLimit })}</li>
        </ul>
      </div>
      <div className="flex flex-col gap-2">
        <h3 className="text-h3">{t('screen.nda.title', { version: panel.ndaVersion })}</h3>
        <NdaText />
      </div>
      <div className="flex flex-col gap-2">
        <h3 className="text-h3">{t('screen.commitment.textTitle', { version: panel.commitmentVersion })}</h3>
        <div
          tabIndex={0}
          role="region"
          aria-label={t('screen.commitment.textLabel')}
          className="max-h-48 overflow-auto rounded-md border border-border bg-background p-4 text-body-sm"
        >
          <p>{t('screen.commitment.text', { login: panel.githubLogin ?? '' })}</p>
          <p className="mt-2">{t('screen.nda.pending')}</p>
        </div>
      </div>
      <label className="flex items-start gap-2 text-body-sm">
        <input type="checkbox" className="mt-1" checked={publicCode} onChange={(event) => setPublicCode(event.target.checked)} />
        <span>{t('screen.matching.apply.n5')}</span>
      </label>
      {needsNotice && <CommitEmailNotice understood={understood} onChange={setUnderstood} />}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" onClick={submit} disabled={!ready || sign.isPending} aria-describedby={`${ids}-why`}>
          {t('screen.commitment.sign')}
        </Button>
        <Button type="button" variant="secondary" onClick={() => setDeclining(true)} disabled={sign.isPending}>
          {t('screen.commitment.decline')}
        </Button>
      </div>
      {!ready && (
        <p id={`${ids}-why`} className="text-caption text-muted-foreground">
          {t(needsNotice ? 'screen.commitment.whyNotice' : 'screen.commitment.why')}
        </p>
      )}
      <Dialog.Root open={declining} onOpenChange={setDeclining}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.commitment.declineTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.product.finding.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description className="text-body-sm">{t('screen.commitment.declineBody')}</Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.commitment.declineBack')}</Dialog.Close>
              <Button type="button" variant="destructive" onClick={confirmDecline} disabled={decline.isPending}>
                {t('screen.commitment.decline')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}

/** A pick that ended, or was signed: why, and the one way forward (UX-DR95, UX-DR103). */
function Ended({ commitment }: { commitment: Commitment }) {
  const joined = commitment.status === 'JOINED'
  const key = joined ? 'screen.commitment.joined' : (ENDED[commitment.endReason ?? ''] ?? 'screen.commitment.ended.other')
  return (
    <div role="status" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <p className="text-label font-semibold">{t(key, { job: commitment.jobTitle })}</p>
      {joined ? (
        <Link to={`/cong-viec/${commitment.jobId}`} className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit')}>
          {t('screen.jobPost.detail.openJob')}
        </Link>
      ) : (
        <Link to="/tim-viec" className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit')}>
          {t('screen.jobPost.detail.findOther')}
        </Link>
      )}
    </div>
  )
}
