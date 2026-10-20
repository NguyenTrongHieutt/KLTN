import { useId, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useFollowUpCommand, useFollowUps, type FollowUp, type FollowUps } from './followUpApi'

const JOB_TYPES = [
  'BUG_FIX',
  'NEW_FEATURE',
  'MODIFICATION',
  'IMPROVEMENT',
  'SECURITY_TESTING',
  'DEPLOYMENT_OPERATIONS',
  'DOCUMENTATION',
  'ASSESSMENT',
] as const

/**
 * JOB-1's "Công việc tiếp theo" (FR-11, UX-DR16): after the Assessment Report is accepted the Mentor
 * drafts follow-up Jobs, each citing one numbered recommendation, and sends them all to the Customer; the
 * Customer opens each as a Draft Job Post in CUS-6, where only their own "Đăng tin" publishes it, or
 * skips it.
 */
export function FollowUpSection({ jobId }: { jobId: string }) {
  const followUps = useFollowUps(jobId)
  const data = followUps.data
  // An auxiliary part of the overview: nothing while loading, when the read fails, or with nothing to do.
  if (!data || (data.audience === 'MENTOR' ? !data.available : data.followUps.length === 0)) {
    return null
  }
  return <Content jobId={jobId} data={data} />
}

function Content({ jobId, data }: { jobId: string; data: FollowUps }) {
  const ids = useId()
  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id={`${ids}-title`} className="text-h2">
        {t('screen.followUps.heading')}
      </h2>
      {data.audience === 'MENTOR' ? <MentorPart jobId={jobId} data={data} /> : <CustomerPart jobId={jobId} data={data} />}
    </section>
  )
}

function typesText(types: string[]): string {
  return types.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(' · ')
}

function MentorPart({ jobId, data }: { jobId: string; data: FollowUps }) {
  const toasts = useToasts()
  const command = useFollowUpCommand(jobId)
  const [editing, setEditing] = useState<FollowUp | 'new'>()
  const [error, setError] = useState<string>()
  const run = (action: Parameters<typeof command.mutate>[0], success?: MessageKey) => {
    setError(undefined)
    command.mutate(action, {
      onSuccess: () => {
        setEditing(undefined)
        if (success) {
          toasts.success(t(success))
        }
      },
      onError: (failure) => setError(problemMessage(failure, { module: 'delivery' }).text),
    })
  }
  return (
    <>
      <p className="text-body-sm">
        {data.submitted ? t('screen.followUps.sent') : t('screen.followUps.mentorLead', { version: data.reportVersionNo ?? 1 })}
      </p>
      {data.followUps.length === 0 && !editing && (
        <p className="text-body-sm text-muted-foreground">{t('screen.followUps.none')}</p>
      )}
      <ul className="flex flex-col gap-2">
        {data.followUps.map((followUp) => (
          <li key={followUp.id} className="flex flex-col gap-1 rounded-md border border-border bg-background p-3 text-body-sm">
            <FollowUpSummary followUp={followUp} />
            {!data.submitted && (
              <div className="flex flex-wrap justify-end gap-2">
                <Button type="button" variant="ghost" size="compact" onClick={() => setEditing(followUp)}>
                  {t('screen.followUps.edit')}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="compact"
                  disabled={command.isPending}
                  onClick={() => run({ action: 'delete', followUpId: followUp.id })}
                >
                  {t('screen.followUps.remove')}
                </Button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {editing && (
        <DraftForm
          key={editing === 'new' ? 'new' : editing.id}
          data={data}
          followUp={editing === 'new' ? undefined : editing}
          pending={command.isPending}
          onCancel={() => setEditing(undefined)}
          onSave={(content) =>
            run(editing === 'new' ? { action: 'create', content } : { action: 'update', followUpId: editing.id, content })
          }
        />
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {!data.submitted && !editing && (
        <div className="flex flex-wrap justify-end gap-3">
          <Button type="button" variant="secondary" onClick={() => setEditing('new')}>
            {t('screen.followUps.add')}
          </Button>
          <Button
            type="button"
            disabled={data.followUps.length === 0 || command.isPending}
            onClick={() => run({ action: 'submit' }, 'screen.followUps.submitted')}
          >
            {t('screen.followUps.submit')}
          </Button>
        </div>
      )}
    </>
  )
}

type DraftContent = {
  recommendationNo: number
  title: string
  jobTypes: string[]
  skillIds: string[]
  scope: string
  criteria: string[]
  effortDays: number
}

function DraftForm({
  data,
  followUp,
  pending,
  onCancel,
  onSave,
}: {
  data: FollowUps
  followUp?: FollowUp
  pending: boolean
  onCancel: () => void
  onSave: (content: DraftContent) => void
}) {
  const ids = useId()
  const [recommendation, setRecommendation] = useState(followUp?.recommendationNo ?? data.recommendations[0]?.number ?? 1)
  const [title, setTitle] = useState(followUp?.title ?? '')
  const [types, setTypes] = useState<string[]>(followUp?.jobTypes ?? [])
  const [scope, setScope] = useState(followUp?.scope ?? '')
  const [criteria, setCriteria] = useState((followUp?.criteria ?? []).join('\n'))
  const [effort, setEffort] = useState(String(followUp?.effortDays ?? ''))
  const lines = criteria
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
  const days = Number(effort)
  const ready =
    title.trim() !== '' &&
    types.length > 0 &&
    scope.trim() !== '' &&
    lines.length > 0 &&
    lines.length <= 10 &&
    Number.isInteger(days) &&
    days >= 1 &&
    days <= 365
  const toggle = (type: string) =>
    setTypes((current) => (current.includes(type) ? current.filter((found) => found !== type) : [...current, type]))
  return (
    <fieldset className="flex flex-col gap-2 rounded-md border border-border bg-background p-3 text-body-sm">
      <legend className="px-1 text-label">
        {followUp ? t('screen.followUps.edit') : t('screen.followUps.add')}
      </legend>
      <label htmlFor={`${ids}-recommendation`} className="text-label">
        {t('screen.followUps.recommendationLabel')}
      </label>
      <select
        id={`${ids}-recommendation`}
        value={recommendation}
        onChange={(event) => setRecommendation(Number(event.target.value))}
        className={inputClass}
      >
        {data.recommendations.map((item) => (
          <option key={item.number} value={item.number}>
            {`${t('screen.followUps.recommendation', { n: item.number })}: ${item.text}`}
          </option>
        ))}
      </select>
      <label htmlFor={`${ids}-title`} className="text-label">
        {t('screen.followUps.title')}
      </label>
      <input
        id={`${ids}-title`}
        value={title}
        maxLength={120}
        onChange={(event) => setTitle(event.target.value)}
        className={inputClass}
      />
      <fieldset className="flex flex-col gap-1">
        <legend className="text-label">{t('screen.followUps.jobTypes')}</legend>
        <div className="flex flex-wrap gap-x-4">
          {JOB_TYPES.map((type) => (
            <label key={type} className="flex min-h-tap-min items-center gap-2">
              <input type="checkbox" className="size-4" checked={types.includes(type)} onChange={() => toggle(type)} />
              {t(`term.jobType${type}.label` as MessageKey)}
            </label>
          ))}
        </div>
      </fieldset>
      <label htmlFor={`${ids}-scope`} className="text-label">
        {t('screen.followUps.scope')}
      </label>
      <textarea
        id={`${ids}-scope`}
        value={scope}
        maxLength={2000}
        onChange={(event) => setScope(event.target.value)}
        className={cn(inputClass, 'min-h-18')}
      />
      <label htmlFor={`${ids}-criteria`} className="text-label">
        {t('screen.followUps.criteria')}
      </label>
      <textarea
        id={`${ids}-criteria`}
        value={criteria}
        onChange={(event) => setCriteria(event.target.value)}
        className={cn(inputClass, 'min-h-18')}
      />
      <label htmlFor={`${ids}-effort`} className="text-label">
        {t('screen.followUps.effort')}
      </label>
      <input
        id={`${ids}-effort`}
        type="number"
        min={1}
        max={365}
        value={effort}
        onChange={(event) => setEffort(event.target.value)}
        className={cn(inputClass, 'w-32')}
      />
      <div className="flex flex-wrap justify-end gap-3">
        <Button type="button" variant="ghost" onClick={onCancel}>
          {t('screen.followUps.cancel')}
        </Button>
        <Button
          type="button"
          disabled={!ready || pending}
          onClick={() =>
            onSave({
              recommendationNo: recommendation,
              title: title.trim(),
              jobTypes: types,
              skillIds: followUp?.skillIds ?? data.skillIds,
              scope: scope.trim(),
              criteria: lines,
              effortDays: days,
            })
          }
        >
          {t('screen.followUps.save')}
        </Button>
      </div>
    </fieldset>
  )
}

function FollowUpSummary({ followUp }: { followUp: FollowUp }) {
  return (
    <>
      <p className="text-label font-semibold break-words">{followUp.title}</p>
      <p className="text-caption text-muted-foreground">
        {[
          t('screen.followUps.recommendation', { n: followUp.recommendationNo }),
          typesText(followUp.jobTypes),
          t('screen.followUps.effortDays', { n: followUp.effortDays }),
        ].join(' · ')}
      </p>
      <p className="break-words whitespace-pre-line">{followUp.scope}</p>
      <ul className="list-disc pl-5">
        {followUp.criteria.map((line, index) => (
          <li key={index} className="break-words">
            {line}
          </li>
        ))}
      </ul>
    </>
  )
}

function CustomerPart({ jobId, data }: { jobId: string; data: FollowUps }) {
  const navigate = useNavigate()
  const command = useFollowUpCommand(jobId)
  const [error, setError] = useState<string>()
  // Opening runs CUS-6's save, so its refusals carry job's reasons.
  const fail = (failure: Error) => {
    const reason = failure instanceof ApiError ? failure.problem.reason : undefined
    const module = reason === 'OPEN_FINDINGS' || reason === 'ASSESSMENT_ALONE' ? 'job' : 'delivery'
    setError(problemMessage(failure, { module }).text)
  }
  return (
    <>
      <p className="text-body-sm">{t('screen.followUps.customerLead')}</p>
      <ul className="flex flex-col gap-2">
        {data.followUps.map((followUp) => (
          <li key={followUp.id} className="flex flex-col gap-1 rounded-md border border-border bg-background p-3 text-body-sm">
            <FollowUpSummary followUp={followUp} />
            {followUp.status === 'SUBMITTED' && (
              <div className="flex flex-wrap justify-end gap-3">
                <Button
                  type="button"
                  variant="secondary"
                  disabled={command.isPending}
                  onClick={() => command.mutate({ action: 'discard', followUpId: followUp.id }, { onError: fail })}
                >
                  {t('screen.followUps.discard')}
                </Button>
                <Button
                  type="button"
                  disabled={command.isPending}
                  onClick={() =>
                    command.mutate(
                      { action: 'open', followUpId: followUp.id },
                      {
                        onSuccess: (result) => {
                          if (result && 'draftJobId' in result) {
                            void navigate(`/cong-viec/${result.draftJobId}`)
                          }
                        },
                        onError: fail,
                      },
                    )
                  }
                >
                  {t('screen.followUps.open')}
                </Button>
              </div>
            )}
            {followUp.status === 'OPENED' && (
              <p className="flex flex-wrap items-center gap-2 text-caption text-muted-foreground">
                {t('screen.followUps.opened')}
                {followUp.draftJobId && (
                  <Link to={`/cong-viec/${followUp.draftJobId}`} className="text-primary underline-offset-4 hover:underline">
                    {t('screen.followUps.openDraft')}
                  </Link>
                )}
              </p>
            )}
            {followUp.status === 'DISCARDED' && (
              <p className="text-caption text-muted-foreground">{t('screen.followUps.discarded')}</p>
            )}
          </li>
        ))}
      </ul>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
    </>
  )
}
