import { CircleCheck, Clock, Info, Lock, Repeat, TriangleAlert, UserPlus, Users } from 'lucide-react'
import { useId, useState } from 'react'
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
import { CrewChangeEditor } from './CrewChange'
import { Avatar, ShareField, TeamPicker } from './crewParts'
import { crewSplit, LARGE_CREW_LIMIT, percentOf, shareProblem, shortName } from './crewShares'

/** JOB-H's line for the Mentor: how many of the Crew confirmed the latest Crew Terms version. */
export function CrewHeaderLine({ panel }: { panel: Panel }) {
  return (
    <p className="flex items-center gap-1.5 text-body-sm">
      <Users aria-hidden="true" className="size-4 text-primary" />
      {t('screen.crew.panel.headerLine', { confirmed: panel.confirmedCount, total: panel.total })}
    </p>
  )
}

/** The line's AD-41 state: "Đã phân công · chưa xác nhận", "Đang tham gia · …", or "Đã rời · …". */
function stateText(row: CrewRow, latest: number | undefined): string {
  if (row.leaving && latest !== undefined) {
    return t('screen.crew.panel.state.LEAVING', { n: latest })
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
  // Story 11.3: "Đổi nhóm thực hiện" on a running Job, with large Crews on.
  const change = panel.change
  const [changing, setChanging] = useState(false)
  const waitingNo = change?.waitingVersionNo ?? undefined
  const pending = add.isPending || remove.isPending || save.isPending || reconfirm.isPending
  const locked = panel.locked
  // FR-75, AD-30: one Student per Crew, or up to five with `features.largeCrews` on (Story 11.1).
  const atLimit = running.length >= (panel.largeCrews ? LARGE_CREW_LIMIT : 1)
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
              {waitingNo !== undefined
                ? t('screen.crew.panel.captionChange', { n: waitingNo, current: change?.inForceVersionNo ?? 1 })
                : latestNo === undefined
                  ? t('screen.crew.panel.captionNone')
                  : t('screen.crew.panel.caption', { n: latestNo })}
            </p>
          </div>
          {change && !changing && (
            <div className="flex flex-col items-end gap-1">
              <Button
                type="button"
                variant="secondary"
                size="compact"
                aria-describedby={change.held ? `${ids}-held` : undefined}
                disabled={change.held || pending}
                onClick={() => setChanging(true)}
              >
                <Repeat aria-hidden="true" className="size-4" />
                {t(waitingNo !== undefined ? 'screen.crew.panel.changeEdit' : 'screen.crew.panel.change')}
              </Button>
              {change.held && (
                <span id={`${ids}-held`} className="max-w-[280px] text-right text-caption text-muted-foreground">
                  {t('screen.crew.panel.changeHeld')}
                </span>
              )}
            </div>
          )}
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
                  {t(panel.largeCrews ? 'screen.crew.panel.limitLarge' : 'screen.crew.panel.limit', { n: LARGE_CREW_LIMIT })}
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

        {change && waitingNo !== undefined && (
          <div role="status" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
            <Clock aria-hidden="true" className="size-5 shrink-0 text-primary" />
            <div className="flex flex-col gap-0.5">
              <p className="font-semibold">
                {t('screen.crew.panel.changeWaiting', { confirmed: change.confirmed, total: change.total })}
              </p>
              <p>{t('screen.crew.panel.changeWaitingBody', { n: waitingNo, current: change.inForceVersionNo ?? 1 })}</p>
            </div>
          </div>
        )}

        {changing && change ? (
          <CrewChangeEditor jobId={jobId} panel={panel} onClose={() => setChanging(false)} />
        ) : locked ? (
          <div role="note" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
            <Lock aria-hidden="true" className="size-5 shrink-0 text-primary" />
            <p>{t(change ? 'screen.crew.panel.lockedRunning' : 'screen.crew.panel.locked')}</p>
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
                          {stateText(row, latestNo)}
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
