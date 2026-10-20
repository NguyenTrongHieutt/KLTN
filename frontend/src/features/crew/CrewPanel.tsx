import { CircleCheck, Info, Lock, TriangleAlert, UserPlus, Users } from 'lucide-react'
import { useId, useState } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/features/team/ConfirmDialog'
import { TestTag } from '@/components/TestTag'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useAddCrewMember,
  useReconfirmCrew,
  useRemoveCrewMember,
  useSaveCrewTerms,
  type CrewCandidate,
  type CrewPanel as Panel,
  type CrewRow,
} from './crewApi'
import { crewSplit, percentOf, shareProblem, shortName, type ShareProblem } from './crewShares'

function Avatar({ name }: { name: string }) {
  const letters = name
    .replace(/^@/, '')
    .trim()
    .split(/\s+/)
    .slice(-2)
    .map((word) => word.charAt(0).toUpperCase())
    .join('')
  return (
    <span aria-hidden="true" className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary-soft text-caption font-semibold text-primary">
      {letters}
    </span>
  )
}

/** JOB-H's line for the Mentor: how many of the Crew confirmed the latest Crew Terms version. */
export function CrewHeaderLine({ panel }: { panel: Panel }) {
  return (
    <p className="flex items-center gap-1.5 text-body-sm">
      <Users aria-hidden="true" className="size-4 text-primary" />
      {t('screen.crew.panel.headerLine', { confirmed: panel.confirmedCount, total: panel.total })}
    </p>
  )
}

/** The line's AD-41 state: "Đã phân công · chưa xác nhận", "Đang tham gia · …", "Tạm ngừng", or "Đã rời · …". */
function stateText(row: CrewRow, latest: number | undefined, owed: boolean): string {
  if (row.status === 'INACTIVE' && owed) {
    return t('screen.crew.panel.state.INACTIVE_OWED')
  }
  if (row.status === 'RELEASED') {
    return row.releasedReason
      ? t(`screen.crew.panel.released.${row.releasedReason}` as MessageKey)
      : t('screen.crew.panel.state.RELEASED')
  }
  if (row.status === 'ACTIVE') {
    return row.confirmedLatest && latest !== undefined
      ? t('screen.crew.panel.state.ACTIVE', { n: latest })
      : t('screen.crew.panel.state.ACTIVE_EARLIER')
  }
  return t(`screen.crew.panel.state.${row.status}` as MessageKey)
}

const STATE_TONE: Record<string, string> = {
  ASSIGNED: 'bg-muted text-foreground',
  ACTIVE: 'bg-success-soft text-success',
  INACTIVE: 'bg-destructive-soft text-destructive',
  RELEASED: 'bg-muted text-muted-foreground',
}

/**
 * The Mentor's Crew panel on JOB-6 (FR-75, AD-41, UX-DR60, N-20): the members with their share fields
 * and states, "Thêm sinh viên" from the Team, the live sum, the example amounts of one Milestone, and
 * "Lưu và gửi cho nhóm xác nhận". Every command carries the `crewVersion` the panel loaded; the
 * parent keys this panel by it, so an answer or a reload starts the fields again from the server.
 */
export function CrewSection({ jobId, panel }: { jobId: string; panel: Panel }) {
  const ids = useId()
  const toasts = useToasts()
  const add = useAddCrewMember(jobId)
  const remove = useRemoveCrewMember(jobId)
  const save = useSaveCrewTerms(jobId)
  const reconfirm = useReconfirmCrew(jobId)
  const running = panel.rows.filter((row) => row.status !== 'RELEASED')
  const [mentorShare, setMentorShare] = useState(panel.mentor.percent == null ? '' : String(panel.mentor.percent))
  const [shares, setShares] = useState<Record<string, string>>(() =>
    Object.fromEntries(running.map((row) => [row.studentId, row.percent == null ? '' : String(row.percent)])),
  )
  const [picking, setPicking] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const pending = add.isPending || remove.isPending || save.isPending || reconfirm.isPending
  const locked = panel.locked
  const atLimit = !panel.largeCrews && running.length >= 1
  const latestNo = panel.latest?.versionNo

  const mentorProblem = shareProblem(mentorShare, true, panel.capPercent)
  const problems = Object.fromEntries(running.map((row) => [row.studentId, shareProblem(shares[row.studentId] ?? '', false, panel.capPercent)]))
  const sum = (percentOf(mentorShare) ?? 0) + running.reduce((total, row) => total + (percentOf(shares[row.studentId] ?? '') ?? 0), 0)
  const fieldsOk = mentorProblem === undefined && running.every((row) => problems[row.studentId] === undefined)
  const sumOk = fieldsOk && sum === 100
  const changed =
    panel.latest == null ||
    percentOf(mentorShare) !== panel.mentor.percent ||
    running.some((row) => row.percent == null || percentOf(shares[row.studentId] ?? '') !== row.percent)
  const canSave = !locked && running.length > 0 && sumOk && changed && !pending

  const failed = (error: unknown, action: string) => {
    if (error instanceof ApiError && error.problem.code === 'STALE') {
      toasts.error(t('error.STALE'))
    } else if (error instanceof ApiError && error.problem.reason === 'SHARES_INVALID') {
      toasts.error(t('reason.job.SHARES_INVALID'))
    } else {
      toasts.error(problemMessage(error, { module: 'job', action }).text)
    }
  }
  const addStudent = (student: CrewCandidate) =>
    add.mutate(
      { studentId: student.studentId, crewVersion: panel.crewVersion },
      {
        onSuccess: () => toasts.success(t('screen.crew.panel.added', { name: student.name })),
        onError: (error) => failed(error, t('screen.crew.panel.addAction')),
      },
    )
  const removeRow = (row: CrewRow) =>
    remove.mutate(
      { rowId: row.rowId, crewVersion: panel.crewVersion },
      {
        onSuccess: () => toasts.success(t('screen.crew.panel.removed', { name: row.name })),
        onError: (error) => failed(error, t('screen.crew.panel.removeAction')),
      },
    )
  const saveTerms = () =>
    save.mutate(
      {
        crewVersion: panel.crewVersion,
        shares: [
          { accountId: panel.mentor.accountId, percent: percentOf(mentorShare) ?? 0 },
          ...running.map((row) => ({ accountId: row.studentId, percent: percentOf(shares[row.studentId] ?? '') ?? 0 })),
        ],
      },
      {
        onSuccess: (saved) => {
          setConfirming(false)
          toasts.success(t('screen.crew.panel.saved', { n: saved.latest?.versionNo ?? 1 }))
        },
        onError: (error) => {
          setConfirming(false)
          failed(error, t('screen.crew.panel.saveAction'))
        },
      },
    )

  const example = sumOk ? panel.milestones.findIndex((milestone) => (milestone.priceVnd ?? 0) > 0) : -1
  const parts =
    example >= 0
      ? crewSplit(
          panel.milestones[example]?.priceVnd ?? 0,
          new Map([
            [panel.mentor.accountId, percentOf(mentorShare) ?? 0],
            ...running.map((row) => [row.studentId, percentOf(shares[row.studentId] ?? '') ?? 0] as [string, number]),
          ]),
          panel.mentor.accountId,
        )
      : undefined
  const usernames = running.flatMap((row) => (row.githubLogin ? [row.githubLogin] : []))

  return (
    <div className="grid w-full max-w-[1120px] gap-5 self-center lg:grid-cols-[minmax(0,1fr)_280px]">
      <section aria-labelledby={`${ids}-crew`} className="flex min-w-0 flex-col gap-4 rounded-lg border border-border bg-card p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex flex-col gap-0.5">
            <h2 id={`${ids}-crew`} className="text-h2">
              {t('screen.crew.panel.title')}
            </h2>
            <p className="text-caption text-muted-foreground">
              {latestNo === undefined ? t('screen.crew.panel.captionNone') : t('screen.crew.panel.caption', { n: latestNo })}
            </p>
          </div>
          {!locked && (
            <div className="flex flex-col items-end gap-1">
              <Button
                type="button"
                variant="secondary"
                size="compact"
                aria-expanded={picking}
                aria-controls={`${ids}-pick`}
                aria-describedby={atLimit ? `${ids}-limit` : undefined}
                disabled={atLimit || pending}
                onClick={() => setPicking(!picking)}
              >
                <UserPlus aria-hidden="true" className="size-4" />
                {t('screen.crew.panel.add')}
              </Button>
              {atLimit && (
                <span id={`${ids}-limit`} className="text-caption text-muted-foreground">
                  {t('screen.crew.panel.limit')}
                </span>
              )}
            </div>
          )}
        </div>

        {panel.reconfirmOwed && (
          <div role="alert" className="flex flex-col gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
            <div className="flex gap-3">
              <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
              <div className="flex flex-col gap-0.5">
                <p className="font-semibold">{t('screen.crew.panel.reconfirmTitle')}</p>
                <p>{t('screen.crew.panel.reconfirmBody')}</p>
              </div>
            </div>
            <Button
              type="button"
              size="compact"
              className="self-start"
              disabled={!panel.crewConfirmed || pending}
              onClick={() =>
                reconfirm.mutate(panel.crewVersion, {
                  onSuccess: () => toasts.success(t('screen.crew.panel.reconfirmed')),
                  onError: (error) => failed(error, t('screen.crew.panel.reconfirmAction')),
                })
              }
            >
              {t('screen.crew.panel.reconfirm')}
            </Button>
          </div>
        )}

        {locked ? (
          <div role="note" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
            <Lock aria-hidden="true" className="size-5 shrink-0 text-primary" />
            <p>{t('screen.crew.panel.locked')}</p>
          </div>
        ) : (
          <div role="note" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
            <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
            <p>{t('screen.crew.panel.n20')}</p>
          </div>
        )}

        {picking && !locked && !atLimit && (
          <TeamPicker id={`${ids}-pick`} team={panel.team} disabled={pending} onPick={addStudent} />
        )}

        <div className="overflow-x-auto">
          <table className="w-full min-w-[560px] text-left text-body-sm">
            <thead className="text-caption text-muted-foreground">
              <tr className="border-b border-border">
                <th scope="col" className="py-2 pr-3 font-semibold">
                  {t('screen.crew.panel.member')}
                </th>
                <th scope="col" className="py-2 pr-3 font-semibold">
                  {t('screen.crew.panel.share')}
                </th>
                <th scope="col" className="py-2 pr-3 font-semibold">
                  {t('screen.crew.panel.status')}
                </th>
                <th scope="col" className="py-2 font-normal">
                  {t('screen.crew.panel.actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr className="border-b border-border align-top">
                <td className="py-3 pr-3">
                  <span className="flex items-center gap-2">
                    <Avatar name={panel.mentor.displayName} />
                    <span className="flex flex-col">
                      <span className="font-semibold">{t('screen.crew.panel.you', { name: panel.mentor.displayName })}</span>
                      <span className="text-caption text-muted-foreground">
                        {panel.mentor.githubLogin
                          ? t('screen.crew.panel.mentorLine', { login: panel.mentor.githubLogin })
                          : t('screen.crew.panel.mentorRole')}
                      </span>
                    </span>
                  </span>
                </td>
                <td className="py-3 pr-3">
                  <ShareField
                    id={`${ids}-mentor`}
                    label={t('screen.crew.panel.shareLabel', { name: panel.mentor.displayName })}
                    value={mentorShare}
                    locked={locked}
                    problem={mentorProblem}
                    cap={panel.capPercent}
                    mentor
                    onChange={setMentorShare}
                  />
                </td>
                <td className="py-3 pr-3">
                  <span className="rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success">
                    {t('screen.crew.panel.mentorRole')}
                  </span>
                </td>
                <td />
              </tr>
              {panel.rows.map((row) => {
                const released = row.status === 'RELEASED'
                const problem = problems[row.studentId]
                return (
                  <tr key={row.rowId} className="border-b border-border align-top last:border-b-0">
                    <td className="py-3 pr-3">
                      <span className="flex items-center gap-2">
                        <Avatar name={row.name} />
                        <span className="flex min-w-0 flex-col">
                          <span className="font-semibold break-words">{row.name}</span>
                          {row.githubLogin && <span className="font-mono text-caption text-muted-foreground">{row.githubLogin}</span>}
                        </span>
                      </span>
                    </td>
                    <td className="py-3 pr-3">
                      {released ? (
                        <span className="text-caption text-muted-foreground">—</span>
                      ) : (
                        <ShareField
                          id={`${ids}-${row.rowId}`}
                          label={t('screen.crew.panel.shareLabel', { name: row.name })}
                          value={shares[row.studentId] ?? ''}
                          locked={locked}
                          problem={problem}
                          cap={panel.capPercent}
                          onChange={(value) => setShares({ ...shares, [row.studentId]: value })}
                        />
                      )}
                    </td>
                    <td className="py-3 pr-3">
                      <span className="flex flex-col items-start gap-1">
                        <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', STATE_TONE[row.status])}>
                          {stateText(row, latestNo, panel.reconfirmOwed)}
                        </span>
                        {!released && (
                          <span className="text-caption text-muted-foreground">
                            {row.signedAt ? t('screen.crew.panel.signed', { date: formatDayMonth(row.signedAt) }) : t('screen.crew.panel.notSigned')}
                          </span>
                        )}
                      </span>
                    </td>
                    <td className="py-3">
                      {!released && !locked && (
                        <Button
                          type="button"
                          variant="ghost"
                          size="compact"
                          aria-label={t('screen.crew.panel.removeNamed', { name: row.name })}
                          disabled={pending}
                          onClick={() => removeRow(row)}
                        >
                          {t('screen.crew.panel.remove')}
                        </Button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {running.length === 0 && <p className="pt-3 text-body-sm text-muted-foreground">{t('screen.crew.panel.empty')}</p>}
        </div>

        {!locked && running.length > 0 && (
          <>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p aria-live="polite" className={cn('flex items-center gap-1.5 text-body-sm font-semibold', !sumOk && 'text-destructive')}>
                {sumOk ? (
                  <CircleCheck aria-hidden="true" className="size-4 text-success" />
                ) : (
                  <TriangleAlert aria-hidden="true" className="size-4" />
                )}
                {sum === 100 ? t('screen.crew.panel.sum', { n: sum }) : t('screen.crew.panel.sumBad', { n: sum })}
              </p>
              <Button type="button" size="compact" disabled={!canSave} onClick={() => (panel.confirmedCount > 0 ? setConfirming(true) : saveTerms())}>
                {t('screen.crew.panel.save')}
              </Button>
            </div>
            {parts && example >= 0 && (
              <p className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-caption">
                <span>
                  {t('screen.crew.panel.example', { n: example + 1 })} ({formatVnd(panel.milestones[example]?.priceVnd ?? 0)} <TestTag />):
                </span>
                {[{ id: panel.mentor.accountId, name: panel.mentor.displayName }, ...running.map((row) => ({ id: row.studentId, name: row.name }))].map(
                  (party, index) => (
                    <span key={party.id}>
                      {index > 0 && '· '}
                      {shortName(party.name)} {formatVnd(parts.get(party.id) ?? 0)} <TestTag />
                    </span>
                  ),
                )}
              </p>
            )}
            <p className="text-caption text-muted-foreground">{t('screen.crew.panel.saveNote')}</p>
          </>
        )}
        <ConfirmDialog
          open={confirming}
          title={t('screen.crew.panel.confirmTitle')}
          body={t('screen.crew.panel.confirmBody')}
          confirmLabel={t('screen.crew.panel.confirmSave')}
          pending={save.isPending}
          onConfirm={saveTerms}
          onClose={() => setConfirming(false)}
        />
      </section>

      <aside className="flex flex-col gap-4">
        <section className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
          <h3 className="text-h3">{t('screen.crew.panel.seesTitle')}</h3>
          <p className="text-body-sm">{t('screen.crew.panel.sees')}</p>
          <p className="font-mono text-body-sm break-words">
            {usernames.length > 0 ? usernames.join(' · ') : t('screen.crew.panel.seesNobody')}
          </p>
          <p className="text-caption text-muted-foreground">{t('screen.crew.panel.seesNote')}</p>
        </section>
        <section className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
          <h3 className="text-h3">{t('screen.crew.panel.rulesTitle')}</h3>
          <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
            <li>{t('screen.crew.panel.ruleWhole')}</li>
            <li>{t('screen.crew.panel.ruleStudent')}</li>
            <li>{t('screen.crew.panel.ruleCap', { cap: panel.capPercent })}</li>
            <li>{t('screen.crew.panel.ruleRemainder')}</li>
          </ul>
        </section>
      </aside>
    </div>
  )
}

function ShareField({
  id,
  label,
  value,
  locked,
  problem,
  cap,
  mentor = false,
  onChange,
}: {
  id: string
  label: string
  value: string
  locked: boolean
  problem: ShareProblem | undefined
  cap: number
  mentor?: boolean
  onChange: (value: string) => void
}) {
  if (locked) {
    return <span className="font-semibold">{value === '' ? '—' : `${value}%`}</span>
  }
  const shown = value !== '' && problem !== undefined
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-1">
        <input
          id={id}
          inputMode="numeric"
          aria-label={label}
          aria-invalid={shown || undefined}
          aria-describedby={`${id}-help`}
          value={value}
          maxLength={3}
          onChange={(event) => onChange(event.target.value.replace(/\D/g, ''))}
          className={cn(inputClass, 'w-18 text-right')}
        />
        <span>%</span>
      </div>
      <span id={`${id}-help`} className={cn('text-caption', shown ? 'text-destructive' : 'text-muted-foreground')}>
        {shown
          ? t(problem === 'mentorOver' ? 'screen.crew.panel.mentorOver' : `screen.crew.panel.${problem}`, { cap })
          : mentor
            ? t('screen.crew.panel.cap', { cap })
            : ''}
      </span>
    </div>
  )
}

const CANDIDATE_TEXT: Record<CrewCandidate['state'], MessageKey> = {
  FREE: 'screen.crew.panel.free',
  IN_CREW: 'screen.crew.panel.inCrew',
  ON_CREW: 'screen.crew.panel.onCrew',
  NOT_VERIFIED: 'screen.crew.panel.notVerified',
}

/** "Thêm sinh viên": the Mentor's Team; only free, verified Students can be picked (UX-DR60). */
function TeamPicker({
  id,
  team,
  disabled,
  onPick,
}: {
  id: string
  team: CrewCandidate[]
  disabled: boolean
  onPick: (student: CrewCandidate) => void
}) {
  return (
    <div id={id} className="flex flex-col gap-1 rounded-md border border-border bg-background p-2">
      <p className="px-2 py-1 text-caption text-muted-foreground">
        {team.length === 0 ? t('screen.crew.panel.pickEmpty') : t('screen.crew.panel.pickCount', { n: team.length })}
      </p>
      <ul aria-label={t('screen.crew.panel.pickLabel')} className="flex flex-col gap-0.5">
        {team.map((student) => (
          <li
            key={student.studentId}
            className={cn('flex items-center gap-2 rounded-md px-2 py-1.5', student.state !== 'FREE' && 'opacity-70')}
          >
            <Avatar name={student.name} />
            <span className="min-w-0 grow text-body-sm break-words">{student.name}</span>
            {student.state === 'FREE' ? (
              <>
                <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">{t('screen.crew.panel.free')}</span>
                <Button
                  type="button"
                  variant="secondary"
                  size="compact"
                  disabled={disabled}
                  aria-label={t('screen.crew.panel.addNamed', { name: student.name })}
                  onClick={() => onPick(student)}
                >
                  {t('screen.crew.panel.pickAdd')}
                </Button>
              </>
            ) : (
              <span className="text-caption text-muted-foreground">
                {student.state === 'ON_CREW' && !student.onCrewJob
                  ? t('screen.crew.panel.onCrewOther')
                  : t(CANDIDATE_TEXT[student.state], { job: student.onCrewJob ?? '' })}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
