import { Plus, Trash2, TriangleAlert } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { Navigate, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { hasChat } from '@/features/chat/chatApi'
import { useCrewPanel } from '@/features/crew/crewApi'
import { CrewHeaderLine, CrewSection } from '@/features/crew/CrewPanel'
import { ByJobRole } from '@/features/job/ByJobRole'
import { JobHeader } from '@/features/job/JobHeader'
import { mentorMeta } from '@/features/job/jobMeta'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob } from '@/features/matching/mentorJobApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { SharedDocsLink } from '@/features/work/docs/SharedDocsPage'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd, groupDigits } from '@/lib/money'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { TestTag } from '@/components/TestTag'
import { CodeRights } from './AgreementContent'
import { useMentorAgreement, useSaveAgreement, useShareAgreement, type AgreementContent, type AgreementDraft } from './agreementApi'
import { digits, problemsOf, technicalOnly, type DraftAccess, type DraftMilestone, type DraftProblem } from './agreementChecks'
import { CustomerAgreement } from './CustomerAgreement'
import { ChangeRequestSection } from './ChangeRequestSection'
import { ExternalAccessSection } from './ExternalAccessSection'
import { ConfirmCard, ConfirmedView, CustomerFeedback } from './MentorReview'
import { inOrder } from './reviewTargets'

/**
 * JOB-6 "Thỏa thuận" (`/cong-viec/{jobId}/thoa-thuan`, FR-16, FR-17, FR-75, AD-29, UX-DR34, UX-DR60):
 * the Mentor drafts and shares the Agreement with the Customer's comments in view, picks the Crew
 * below the editor, and confirms once the Customer approved and the Crew confirmed; the Customer
 * reviews the shared versions (`CustomerAgreement`). The caller's role in the Job picks which (Story 20.6).
 */
export function AgreementPage() {
  const { jobId = '' } = useParams()
  return (
    <ByJobRole
      jobId={jobId || undefined}
      customer={() => <CustomerAgreement jobId={jobId} />}
      mentor={() => <MentorAgreement jobId={jobId} />}
    />
  )
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {children}
    </div>
  )
}

function MentorAgreement({ jobId }: { jobId: string }) {
  const job = useMentorJob(jobId)
  const draft = useMentorAgreement(jobId)
  const crew = useCrewPanel(jobId)
  usePageTitle(job.data ? t('screen.agreement.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && !hasChat(job.data.state)) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <Frame>
      <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
        {job.data && (
          <JobHeader
            title={job.data.title}
            state={job.data.state}
            meta={mentorMeta(job.data)}
            status={crew.data && <CrewHeaderLine panel={crew.data} />}
          >
            <MentorJobTabs
              jobId={job.data.id}
              state={job.data.state}
              current="agreement"
              payment={job.data.agreementConfirmed}
            />
          </JobHeader>
        )}
      </RegionState>
      <RegionState query={draft} skeleton={<Skeleton className="h-96 w-full" />}>
        {draft.data &&
          (draft.data.confirmed ? (
            <ConfirmedView confirmed={draft.data.confirmed} />
          ) : (
            <Editor key={draft.data.revision} jobId={jobId} draft={draft.data} />
          ))}
      </RegionState>
      <SharedDocsLink jobId={jobId} />
      {draft.data?.confirmed && <ExternalAccessSection jobId={jobId} />}
      {draft.data?.confirmed && <ChangeRequestSection jobId={jobId} />}
      <RegionState query={crew} skeleton={<Skeleton className="h-64 w-full" />}>
        {crew.data && <CrewSection key={crew.data.crewVersion} jobId={jobId} panel={crew.data} />}
      </RegionState>
      {job.data && draft.data && crew.data && !draft.data.confirmed && (
        <ConfirmCard job={job.data} draft={draft.data} panel={crew.data} />
      )}
    </Frame>
  )
}

function toMilestones(content: AgreementContent): DraftMilestone[] {
  return content.milestones.map((milestone) => ({
    id: milestone.id,
    title: milestone.title,
    price: milestone.priceVnd == null ? '' : String(milestone.priceVnd),
    dueOn: milestone.dueOn ?? '',
    revisionRounds: milestone.revisionRounds,
    criteria: milestone.criteria.map((criterion) => ({ ...criterion })),
  }))
}

/**
 * The Mentor's editor (FR-16, UX-DR34, UX-DR44): every entity keeps the UUID it was created with, the
 * draft saves only on "Lưu bản nháp", and "Gửi cho khách hàng" waits until nothing is missing.
 */
function Editor({ jobId, draft }: { jobId: string; draft: AgreementDraft }) {
  const ids = useId()
  const content = draft.content
  const assessment = content.assessment
  const [scope, setScope] = useState(content.scope)
  const [price, setPrice] = useState(content.priceVnd == null ? '' : String(content.priceVnd))
  const [skills, setSkills] = useState(content.skills.map((skill) => skill.id))
  const [milestones, setMilestones] = useState(() => toMilestones(content))
  const [access, setAccess] = useState<DraftAccess[]>(() => content.externalAccess.map((entry) => ({ ...entry })))
  const [dirty, setDirty] = useState(false)
  const save = useSaveAgreement(jobId)
  const share = useShareAgreement(jobId)
  const toasts = useToasts()
  const problems = problemsOf({ scope, price, milestones, access }, assessment)
  const total = milestones.reduce((sum, milestone) => sum + (digits(milestone.price) ?? 0), 0)
  const agreed = digits(price)
  const pending = save.isPending || share.isPending
  const defaultRounds = milestones[0]?.revisionRounds ?? 2

  const touch = <T,>(setter: (value: T) => void) => (value: T) => {
    setter(value)
    setDirty(true)
  }
  const setMilestone = (index: number, next: Partial<DraftMilestone>) =>
    touch(setMilestones)(milestones.map((milestone, m) => (m === index ? { ...milestone, ...next } : milestone)))

  const body = () => ({
    revision: draft.revision,
    scope,
    priceVnd: digits(price),
    skillIds: skills,
    milestones: milestones.map((milestone) => ({
      id: milestone.id,
      title: milestone.title,
      priceVnd: digits(milestone.price),
      dueOn: milestone.dueOn === '' ? undefined : milestone.dueOn,
      revisionRounds: milestone.revisionRounds,
      criteria: milestone.criteria,
    })),
    externalAccess: access,
  })
  const failed = (error: unknown) =>
    toasts.error(
      error instanceof ApiError && error.problem.code === 'STALE'
        ? t('screen.agreement.stale')
        : problemMessage(error, { module: 'job' }).text,
    )
  const saveDraft = () => save.mutate(body(), { onSuccess: () => toasts.success(t('screen.agreement.saved')), onError: failed })
  const shareDraft = () => {
    const send = (revision: number) =>
      share.mutate(revision, {
        onSuccess: (shared) => toasts.success(t('screen.agreement.shared', { n: shared.versions.at(-1)?.versionNo ?? 1 })),
        onError: failed,
      })
    if (dirty) {
      save.mutate(body(), { onSuccess: (saved) => send(saved.revision), onError: failed })
    } else {
      send(draft.revision)
    }
  }
  const last = draft.versions.at(-1)

  return (
    <div className="flex w-full max-w-[860px] flex-col gap-5 self-center">
      <CustomerFeedback
        review={draft.review}
        content={content}
        onApplyOrder={(order) => {
          touch(setMilestones)(inOrder(milestones, order))
          toasts.success(t('screen.agreement.review.orderApplied'))
        }}
      />
      <div className="flex flex-wrap items-center justify-between gap-2 text-body-sm text-muted-foreground">
        <span>
          {last
            ? t('screen.agreement.lastShared', { n: last.versionNo, time: formatDayMonthTime(last.sharedAt) })
            : t('screen.agreement.notShared')}
        </span>
        {draft.quote && (
          <span>
            {t('screen.agreement.quote', { price: formatVnd(draft.quote.priceVnd), days: draft.quote.timelineDays })} <TestTag />
          </span>
        )}
      </div>

      <section aria-labelledby={`${ids}-scope`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
        <h2 id={`${ids}-scope`} className="text-h2">
          {t('screen.agreement.scope')}
        </h2>
        {assessment ? (
          <p className="text-body-sm">{t('screen.agreement.assessmentNote')}</p>
        ) : (
          <>
            <textarea
              aria-labelledby={`${ids}-scope`}
              aria-describedby={`${ids}-scope-help`}
              value={scope}
              maxLength={4000}
              onChange={(event) => touch(setScope)(event.target.value)}
              className={cn(inputClass, 'min-h-24')}
            />
            <p id={`${ids}-scope-help`} className="text-caption text-muted-foreground">
              {t('screen.agreement.scopeHelp')}
            </p>
          </>
        )}
        {content.codeJob && <p className="rounded-md bg-muted p-3 text-body-sm">{t('screen.agreement.codeStatement')}</p>}
        <div className="grid gap-3 md:grid-cols-2">
          <div className="flex flex-col gap-1">
            <label htmlFor={`${ids}-price`} className="text-label">
              {t('screen.agreement.price')}
            </label>
            <input
              id={`${ids}-price`}
              inputMode="numeric"
              value={groupDigits(price)}
              onChange={(event) => touch(setPrice)(event.target.value)}
              aria-describedby={`${ids}-price-help`}
              className={inputClass}
            />
            <span id={`${ids}-price-help`} className="text-caption text-muted-foreground">
              {t('screen.agreement.priceHelp')} <TestTag />
            </span>
          </div>
          {!assessment && (
            <div className="flex flex-col gap-1">
              <label htmlFor={`${ids}-skills`} className="text-label">
                {t('screen.agreement.skills')}
              </label>
              <SkillPicker
                id={`${ids}-skills`}
                value={skills}
                onChange={touch(setSkills)}
                known={content.skills.map((skill) => ({ id: skill.id, name: skill.name, active: true }))}
                max={10}
              />
            </div>
          )}
        </div>
      </section>

      <section aria-labelledby={`${ids}-milestones`} className="flex flex-col gap-3">
        <h2 id={`${ids}-milestones`} className="text-h2">
          {t('screen.agreement.milestones')}
        </h2>
        {milestones.map((milestone, m) => (
          <MilestoneCard
            key={milestone.id}
            index={m}
            milestone={milestone}
            assessment={assessment}
            onChange={(next) => setMilestone(m, next)}
            onRemove={() => touch(setMilestones)(milestones.filter((_, other) => other !== m))}
          />
        ))}
        <div className="flex flex-wrap items-center justify-between gap-2 text-body-sm">
          <span>
            {t('screen.agreement.total', { total: formatVnd(total) })} <TestTag />
            {' · '}
            <span className={cn(agreed !== undefined && agreed !== total && 'font-semibold text-warning')}>
              {t(agreed === total ? 'screen.agreement.matchesPrice' : 'screen.agreement.differsPrice')}
            </span>
            {draft.quote && (
              <>
                {' · '}
                {t(total === draft.quote.priceVnd ? 'screen.agreement.matchesQuote' : 'screen.agreement.differsQuote')}
              </>
            )}
          </span>
          {!assessment && (
            <Button
              type="button"
              variant="ghost"
              size="compact"
              onClick={() =>
                touch(setMilestones)([
                  ...milestones,
                  {
                    id: crypto.randomUUID(),
                    title: '',
                    price: '',
                    dueOn: '',
                    revisionRounds: defaultRounds,
                    criteria: [{ id: crypto.randomUUID(), statement: '', checkSteps: '' }],
                  },
                ])
              }
            >
              <Plus aria-hidden="true" className="size-4" />
              {t('screen.agreement.addMilestone')}
            </Button>
          )}
        </div>
      </section>

      {!assessment && <AccessEditor access={access} onChange={touch(setAccess)} />}

      <CodeRights />

      {problems.length > 0 && <ProblemSummary problems={problems} />}
      <div className="flex flex-wrap justify-between gap-3">
        <Button type="button" variant="secondary" onClick={saveDraft} disabled={pending || !dirty}>
          {t('screen.agreement.save')}
        </Button>
        <Button type="button" onClick={shareDraft} disabled={pending || problems.length > 0}>
          {t('screen.agreement.share')}
        </Button>
      </div>
    </div>
  )
}

function MilestoneCard({
  index,
  milestone,
  assessment,
  onChange,
  onRemove,
}: {
  index: number
  milestone: DraftMilestone
  assessment: boolean
  onChange: (next: Partial<DraftMilestone>) => void
  onRemove: () => void
}) {
  const ids = useId()
  const n = index + 1
  const setCriterion = (c: number, next: Partial<DraftMilestone['criteria'][number]>) =>
    onChange({ criteria: milestone.criteria.map((criterion, other) => (other === c ? { ...criterion, ...next } : criterion)) })
  return (
    <article aria-labelledby={`${ids}-title`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <div className="flex items-center justify-between gap-2">
        <h3 id={`${ids}-title`} className="text-h3">
          {t('screen.agreement.milestone', { n })}
        </h3>
        {!assessment && (
          <Button type="button" variant="ghost" size="compact" onClick={onRemove} aria-label={t('screen.agreement.removeMilestone', { n })}>
            <Trash2 aria-hidden="true" className="size-4" />
          </Button>
        )}
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <Field id={`${ids}-name`} label={t('screen.agreement.milestoneTitle')}>
          {assessment ? (
            <p id={`${ids}-name`} className="text-body-sm">
              {milestone.title}
            </p>
          ) : (
            <input id={`${ids}-name`} value={milestone.title} maxLength={120} onChange={(event) => onChange({ title: event.target.value })} className={inputClass} />
          )}
        </Field>
        <Field id={`${ids}-price`} label={t('screen.agreement.milestonePrice')}>
          <input
            id={`${ids}-price`}
            inputMode="numeric"
            value={groupDigits(milestone.price)}
            onChange={(event) => onChange({ price: event.target.value })}
            className={inputClass}
          />
        </Field>
        <Field id={`${ids}-due`} label={t('screen.agreement.milestoneDue')}>
          <input id={`${ids}-due`} type="date" value={milestone.dueOn} onChange={(event) => onChange({ dueOn: event.target.value })} className={inputClass} />
        </Field>
        <Field id={`${ids}-rounds`} label={t('screen.agreement.rounds')}>
          <input
            id={`${ids}-rounds`}
            type="number"
            min={0}
            max={10}
            value={milestone.revisionRounds}
            disabled={assessment}
            onChange={(event) => onChange({ revisionRounds: Math.max(0, Math.min(10, Number(event.target.value) || 0)) })}
            className={inputClass}
          />
        </Field>
      </div>
      {milestone.criteria.map((criterion, c) => (
        <div key={criterion.id} className="flex flex-col gap-2 rounded-md border border-border bg-background p-3">
          <div className="flex items-center justify-between gap-2">
            <label htmlFor={`${ids}-c${c}`} className="text-label">
              {t('screen.agreement.criterion', { n: c + 1 })}
            </label>
            {!assessment && (
              <Button
                type="button"
                variant="ghost"
                size="compact"
                aria-label={t('screen.agreement.removeCriterion', { n: c + 1 })}
                onClick={() => onChange({ criteria: milestone.criteria.filter((_, other) => other !== c) })}
              >
                <Trash2 aria-hidden="true" className="size-4" />
              </Button>
            )}
          </div>
          {assessment ? (
            <p id={`${ids}-c${c}`} className="text-body-sm">
              {criterion.statement}
            </p>
          ) : (
            <input
              id={`${ids}-c${c}`}
              value={criterion.statement}
              maxLength={500}
              onChange={(event) => setCriterion(c, { statement: event.target.value })}
              className={inputClass}
            />
          )}
          {!assessment && technicalOnly(criterion.statement) && (
            <p className="text-body-sm text-warning">{t('screen.agreement.technical')}</p>
          )}
          <label htmlFor={`${ids}-s${c}`} className="text-label">
            {t('screen.agreement.steps')}
          </label>
          {assessment ? (
            <p id={`${ids}-s${c}`} className="text-body-sm whitespace-pre-line">
              {criterion.checkSteps}
            </p>
          ) : (
            <>
              <textarea
                id={`${ids}-s${c}`}
                value={criterion.checkSteps}
                maxLength={2000}
                onChange={(event) => setCriterion(c, { checkSteps: event.target.value })}
                aria-describedby={`${ids}-s${c}-help`}
                className={cn(inputClass, 'min-h-18')}
              />
              <span id={`${ids}-s${c}-help`} className="text-caption text-muted-foreground">
                {t('screen.agreement.stepsHelp')}
              </span>
            </>
          )}
        </div>
      ))}
      {!assessment && (
        <Button
          type="button"
          variant="ghost"
          size="compact"
          className="self-start"
          onClick={() => onChange({ criteria: [...milestone.criteria, { id: crypto.randomUUID(), statement: '', checkSteps: '' }] })}
        >
          <Plus aria-hidden="true" className="size-4" />
          {t('screen.agreement.addCriterion')}
        </Button>
      )}
    </article>
  )
}

function Field({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-label">
        {label}
      </label>
      {children}
    </div>
  )
}

function AccessEditor({ access, onChange }: { access: DraftAccess[]; onChange: (next: DraftAccess[]) => void }) {
  const ids = useId()
  const set = (index: number, next: Partial<DraftAccess>) =>
    onChange(access.map((entry, a) => (a === index ? { ...entry, ...next } : entry)))
  return (
    <section aria-labelledby={`${ids}-heading`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id={`${ids}-heading`} className="text-h2">
        {t('screen.agreement.externalAccess')}
      </h2>
      {access.length === 0 && <p className="text-body-sm text-muted-foreground">{t('screen.agreement.noAccess')}</p>}
      {access.map((entry, a) => (
        <div key={entry.id} className="grid gap-2 rounded-md border border-border bg-background p-3 md:grid-cols-[1fr_1.4fr_auto_auto_auto] md:items-end">
          <Field id={`${ids}-sys${a}`} label={t('screen.agreement.system')}>
            <input id={`${ids}-sys${a}`} value={entry.system} maxLength={120} onChange={(event) => set(a, { system: event.target.value })} className={inputClass} />
          </Field>
          <Field id={`${ids}-pur${a}`} label={t('screen.agreement.purpose')}>
            <input id={`${ids}-pur${a}`} value={entry.purpose} maxLength={300} onChange={(event) => set(a, { purpose: event.target.value })} className={inputClass} />
          </Field>
          <Field id={`${ids}-env${a}`} label={t('screen.agreement.environment')}>
            <select
              id={`${ids}-env${a}`}
              value={entry.environment}
              onChange={(event) => set(a, { environment: event.target.value as DraftAccess['environment'] })}
              className={inputClass}
            >
              <option value="NON_PRODUCTION">{t('screen.agreement.envNON_PRODUCTION')}</option>
              <option value="PRODUCTION">{t('screen.agreement.envPRODUCTION')}</option>
            </select>
          </Field>
          <Field id={`${ids}-lvl${a}`} label={t('screen.agreement.access')}>
            <select
              id={`${ids}-lvl${a}`}
              value={entry.accessLevel}
              onChange={(event) => set(a, { accessLevel: event.target.value as DraftAccess['accessLevel'] })}
              className={inputClass}
            >
              {(['READ', 'WRITE', 'ADMIN'] as const).map((level) => (
                <option key={level} value={level}>
                  {t(`screen.agreement.access${level}` as MessageKey)}
                </option>
              ))}
            </select>
          </Field>
          <Button
            type="button"
            variant="ghost"
            size="compact"
            aria-label={t('screen.agreement.removeAccess', { n: a + 1 })}
            onClick={() => onChange(access.filter((_, other) => other !== a))}
          >
            <Trash2 aria-hidden="true" className="size-4" />
          </Button>
        </div>
      ))}
      <Button
        type="button"
        variant="ghost"
        size="compact"
        className="self-start"
        onClick={() =>
          onChange([...access, { id: crypto.randomUUID(), system: '', purpose: '', environment: 'NON_PRODUCTION', accessLevel: 'READ' }])
        }
      >
        <Plus aria-hidden="true" className="size-4" />
        {t('screen.agreement.addAccess')}
      </Button>
    </section>
  )
}

function ProblemSummary({ problems }: { problems: DraftProblem[] }) {
  return (
    <div role="alert" className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4 text-body-sm">
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
      <div className="flex flex-col gap-1">
        <p>{t('screen.agreement.summary', { n: problems.length })}</p>
        <ul className="list-disc pl-5">
          {problems.map((problem, index) => (
            <li key={`${problem.code}-${index}`}>
              {t(`screen.agreement.problem.${problem.code}` as MessageKey, {
                n: problem.milestone ?? problem.access ?? 0,
                c: problem.criterion ?? 0,
              })}
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
